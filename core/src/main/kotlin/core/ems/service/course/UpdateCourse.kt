package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Course
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.idToLongOrInvalidReq
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*


@RestController
@RequestMapping("/v2")
class UpdateCourse {
    private val log = KotlinLogging.logger {}

    data class Req(
        @param:JsonProperty("title") @field:NotBlank @field:Size(max = 100) val title: String,
        @param:JsonProperty("alias") @field:Size(max = 100) val alias: String?,
        @param:JsonProperty("color") @field:NotBlank @field:Size(max = 20) val color: String,
        @param:JsonProperty("course_code") @field:Size(max = 100) val courseCode: String?,
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PutMapping("/courses/{courseId}")
    fun controller(@PathVariable("courseId") courseIdStr: String, @Valid @RequestBody dto: Req, caller: EasyUser) {
        log.info { "Update course $courseIdStr by ${caller.id}" }

        val courseId = courseIdStr.idToLongOrInvalidReq()
        caller.assertAccess {
            teacherOnCourse(courseId)
        }

        // Update title only if admin
        putCourse(courseId, dto, caller.isAdmin())
    }

    private fun putCourse(courseId: Long, dto: Req, isAdmin: Boolean) = transaction {
        Course.update({ Course.id.eq(courseId) }) {
            if (isAdmin) {
                it[title] = dto.title
                it[courseCode] = dto.courseCode
            }
            it[alias] = dto.alias
            it[color] = dto.color
        }
    }
}