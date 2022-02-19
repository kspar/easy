package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.StudentCourseAccess
import core.db.StudentCourseGroup
import core.db.StudentPendingAccess
import core.db.StudentPendingCourseGroup
import core.ems.service.assertTeacherOrAdminHasAccessToCourse
import core.ems.service.canTeacherOrAdminAccessCourseGroup
import core.ems.service.idToLongOrInvalidReq
import core.exception.ForbiddenException
import core.exception.ReqError
import mu.KotlinLogging
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Size

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class RemoveStudentsFromCourseController {

    data class Req(
        @JsonProperty("active_students") @field:Valid
        val activeStudents: List<ActiveStudentReq> = emptyList(),
        @JsonProperty("pending_students") @field:Valid
        val pendingStudents: List<PendingStudentReq> = emptyList(),
    )

    data class ActiveStudentReq(
        @JsonProperty("id") @field:NotBlank @field:Size(max = 100)
        val id: String,
    )

    data class PendingStudentReq(
        @JsonProperty("email") @field:NotBlank @field:Size(max = 100)
        val email: String,
    )

    data class Resp(
        @JsonProperty("removed_active_count")
        val removedActiveCount: Int,
        @JsonProperty("removed_pending_count")
        val removedPendingCount: Int,
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @DeleteMapping("/courses/{courseId}/students")
    fun controller(
        @PathVariable("courseId") courseIdStr: String,
        @Valid @RequestBody body: Req,
        caller: EasyUser
    ): Resp {

        log.debug {
            "Removing active students ${body.activeStudents.map { it.id }} and " +
                    "pending students ${body.pendingStudents.map { it.email }} from course $courseIdStr by ${caller.id}"
        }
        val courseId = courseIdStr.idToLongOrInvalidReq()

        /*
        Users can only remove students whom they can access i.e. the student must
        - be on a course which the user can access,
        - not be in a group or if they are in any groups, at least one of the groups must be accessible to the user.
         */

        assertTeacherOrAdminHasAccessToCourse(caller, courseId)

        val studentIds = body.activeStudents.map { it.id }
        val pendingStudentEmails = body.pendingStudents.map { it.email }
        assertCallerCanAccessStudents(caller, courseId, studentIds, pendingStudentEmails)

        val (deleted, pendingDeleted) = deleteStudentsFromCourse(courseId, studentIds, pendingStudentEmails)
        log.debug { "Removed $deleted active students and $pendingDeleted pending students" }
        return Resp(deleted, pendingDeleted)
    }

    private fun assertCallerCanAccessStudents(
        caller: EasyUser, courseId: Long,
        studentIds: List<String>, pendingStudentEmails: List<String>,
    ) {
        transaction {
            // Active students
            // studentId -> [groupIds]
            val studentGroups: Map<String, List<Long>> =
                StudentCourseGroup.select {
                    StudentCourseGroup.course eq courseId and
                            StudentCourseGroup.student.inList(studentIds)
                }.map {
                    it[StudentCourseGroup.student].value to it[StudentCourseGroup.courseGroup].value
                }.groupBy({ it.first }) { it.second }

            // Pending students
            // email -> [groupIds]
            val pendingStudentGroups = StudentPendingCourseGroup.select {
                StudentPendingCourseGroup.course eq courseId and
                        StudentPendingCourseGroup.email.inList(pendingStudentEmails)
            }.map {
                it[StudentPendingCourseGroup.email] to it[StudentPendingCourseGroup.courseGroup].value
            }.groupBy({ it.first }) { it.second }


            // studentIdentifier -> [groupIds]
            val allStudentsGroups = studentGroups + pendingStudentGroups

            // Optimisation: build a map of all seen groups: {groupId -> callerCanAccess}
            val groupAccessMap = allStudentsGroups.flatMap { it.value }.toSet().associateWith {
                canTeacherOrAdminAccessCourseGroup(caller, courseId, it)
            }

            // Check whether the caller can access at least one group for each student (who is in a group)
            allStudentsGroups.forEach { (studentId, groupIds) ->
                val canAccessStudentsGroup = groupIds.any {
                    groupAccessMap[it]
                        ?: false.also { log.warn { "Cannot find group $it in access map $groupAccessMap" } }
                }
                if (!canAccessStudentsGroup) {
                    throw ForbiddenException(
                        "Not allowed to remove student $studentId from course $courseId due to group restrictions",
                        ReqError.NO_GROUP_ACCESS, "studentIdentifier" to studentId
                    )
                }
            }
        }
    }

    private fun deleteStudentsFromCourse(
        courseId: Long, activeStudentIds: List<String>,
        pendingStudentEmails: List<String>
    ): Pair<Int, Int> {

        return transaction {
            val removed = StudentCourseAccess.deleteWhere {
                StudentCourseAccess.course eq courseId and
                        StudentCourseAccess.student.inList(activeStudentIds)
            }
            val removedPending = StudentPendingAccess.deleteWhere {
                StudentPendingAccess.course eq courseId and
                        StudentPendingAccess.email.inList(pendingStudentEmails)
            }
            removed to removedPending
        }
    }
}
