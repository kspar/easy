package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.StudentCourseAccess
import core.db.StudentCourseGroup
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

    data class Req(@JsonProperty("students") @field:Valid val students: List<StudentReq>)

    data class StudentReq(@JsonProperty("id") @field:NotBlank @field:Size(max = 100) val id: String)

    data class Resp(
        @JsonProperty("removed_count") val removedCount: Int
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @DeleteMapping("/courses/{courseId}/students")
    fun controller(
        @PathVariable("courseId") courseIdStr: String,
        @Valid @RequestBody body: Req,
        caller: EasyUser
    ): Resp {

        log.debug { "Removing students $body from course $courseIdStr by ${caller.id}" }
        val courseId = courseIdStr.idToLongOrInvalidReq()

        /*
        Users can only remove students whom they can access i.e. the student must
        - be on a course which the user can access,
        - not be in a group or if they are in any groups, at least one of the groups must be accessible to the user.
         */

        assertTeacherOrAdminHasAccessToCourse(caller, courseId)

        val studentIds = body.students.map { it.id }
        assertCallerCanAccessStudents(caller, studentIds, courseId)

        val deletedCount = deleteStudentsFromCourse(courseId, studentIds)
        log.debug { "Removed $deletedCount students" }
        return Resp(deletedCount)
    }
}

private fun assertCallerCanAccessStudents(caller: EasyUser, studentIds: List<String>, courseId: Long) {
    transaction {
        // studentId -> [groupIds]
        val studentGroups: Map<String, List<Long>> = (StudentCourseGroup).select {
            StudentCourseGroup.course eq courseId and
                    (StudentCourseGroup.student.inList(studentIds))
        }.map {
            // studentId -> groupId
            it[StudentCourseGroup.student] to it[StudentCourseGroup.courseGroup].value
        }.groupBy({ it.first }, { it.second })

        // groupId -> callerCanAccess
        val groupAccessMap = studentGroups.flatMap { it.value }.toSet().associateWith {
            canTeacherOrAdminAccessCourseGroup(caller, courseId, it)
        }

        // Check whether caller can access at least one group for each student who is in a group
        studentGroups.forEach { (studentId, groupIds) ->
            val canAccessStudentsGroup = groupIds.any {
                groupAccessMap[it]
                    ?: false.also { log.warn { "Cannot find group $it in access map $groupAccessMap" } }
            }
            if (!canAccessStudentsGroup && groupIds.isNotEmpty()) {
                throw ForbiddenException(
                    "Not allowed to remove student $studentId from course $courseId due to group restrictions",
                    ReqError.NO_GROUP_ACCESS
                )
            }
        }
    }
}

private fun deleteStudentsFromCourse(courseId: Long, studentIds: List<String>): Int {
    return transaction {
        StudentCourseAccess.deleteWhere {
            StudentCourseAccess.course eq courseId and
                    (StudentCourseAccess.student.inList(studentIds))
        }
    }
}
