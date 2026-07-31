package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.TeacherCourseAccess
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.idToLongOrInvalidReq
import core.ems.service.teacherExists
import core.exception.InvalidRequestException
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/v2")
class RemoveTeachersFromCourseController {
    private val log = KotlinLogging.logger {}

    data class Req(
        @param:JsonProperty("teachers") @field:Valid val teachers: List<TeacherIdReq>
    )

    data class TeacherIdReq(
        @param:JsonProperty("id") @field:NotBlank @field:Size(max = 100) val id: String
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @DeleteMapping("/courses/{courseId}/teachers")
    fun controller(
        @PathVariable("courseId") courseIdStr: String,
        @Valid @RequestBody teachers: Req,
        caller: EasyUser
    ) {

        log.info { "Removing teachers ${teachers.teachers.map { it.id }} from course $courseIdStr" }

        val courseId = courseIdStr.idToLongOrInvalidReq()

        caller.assertAccess {
            teacherOnCourse(courseId)
        }

        deleteTeachersFromCourse(teachers, courseId)
    }

    private fun deleteTeachersFromCourse(teachers: Req, courseId: Long) = transaction {
        teachers.teachers.forEach { teacher ->
            if (!teacherExists(teacher.id)) {
                throw InvalidRequestException("Teacher not found: $teacher")
            }
        }

        val teachersWithAccess = teachers.teachers.filter {
            TeacherCourseAccess.selectAll()
                .where { TeacherCourseAccess.teacher eq it.id and (TeacherCourseAccess.course eq courseId) }
                .count() > 0
        }


        teachersWithAccess.forEach { teacher ->
            TeacherCourseAccess.deleteWhere {
                TeacherCourseAccess.teacher eq teacher.id and (TeacherCourseAccess.course eq courseId)
            }
        }

        log.info { "Removing access from teachers (the rest already have no access): $teachersWithAccess" }

    }
}