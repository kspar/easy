package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.StudentCourseAccess
import core.db.StudentPendingAccess
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.cache.CachingService
import core.ems.service.idToLongOrInvalidReq
import mu.KotlinLogging
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Size

@RestController
@RequestMapping("/v2")
class RemoveStudentsFromCourseController(val cachingService: CachingService) {
    private val log = KotlinLogging.logger {}

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

        log.info {
            "Removing active students ${body.activeStudents.map { it.id }} and " +
                    "pending students ${body.pendingStudents.map { it.email }} from course $courseIdStr by ${caller.id}"
        }
        val courseId = courseIdStr.idToLongOrInvalidReq()

        /*
        Users can only remove students whom they can access i.e. the student must be on a course which the user can access
         */

        caller.assertAccess { teacherOnCourse(courseId) }

        val studentIds = body.activeStudents.map { it.id }
        val pendingStudentEmails = body.pendingStudents.map { it.email }

        val (deleted, pendingDeleted) = deleteStudentsFromCourse(courseId, studentIds, pendingStudentEmails)
        log.info { "Removed $deleted active students and $pendingDeleted pending students" }

        return Resp(deleted, pendingDeleted)
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
