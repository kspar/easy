package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.StudentCourseAccess
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
    )

    data class ActiveStudentReq(
        @JsonProperty("id") @field:NotBlank @field:Size(max = 100)
        val id: String,
    )

    data class Resp(
        @JsonProperty("removed_active_count")
        val removedActiveCount: Int,
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @DeleteMapping("/courses/{courseId}/students")
    fun controller(
        @PathVariable("courseId") courseIdStr: String,
        @Valid @RequestBody body: Req,
        caller: EasyUser
    ): Resp {

        log.info {
            "Removing active students ${body.activeStudents.map { it.id }} from course $courseIdStr by ${caller.id}"
        }
        val courseId = courseIdStr.idToLongOrInvalidReq()

        caller.assertAccess { teacherOnCourse(courseId) }

        val studentIds = body.activeStudents.map { it.id }

        val deleted = deleteStudentsFromCourse(courseId, studentIds)
        log.info { "Removed $deleted active students" }

        return Resp(deleted)
    }

    private fun deleteStudentsFromCourse(courseId: Long, activeStudentIds: List<String>): Int {
        return transaction {
            StudentCourseAccess.deleteWhere {
                StudentCourseAccess.course eq courseId and
                        StudentCourseAccess.student.inList(activeStudentIds)
            }
        }
    }
}
