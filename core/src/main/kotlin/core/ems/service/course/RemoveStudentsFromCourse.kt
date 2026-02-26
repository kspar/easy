package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.StudentCourseAccess
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.cache.CachingService
import core.ems.service.idToLongOrInvalidReq
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/v2")
class RemoveStudentsFromCourseController(val cachingService: CachingService) {
    private val log = KotlinLogging.logger {}

    data class Req(
        @param:JsonProperty("active_students") @field:Valid
        val activeStudents: List<ActiveStudentReq> = emptyList(),
    )

    data class ActiveStudentReq(
        @param:JsonProperty("id") @field:NotBlank @field:Size(max = 100)
        val id: String,
    )

    data class Resp(
        @get:JsonProperty("removed_active_count")
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
