package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Course
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.idToLongOrInvalidReq
import mu.KotlinLogging
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Size

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class UpdateCourse {

    data class Req(
        @JsonProperty("title") @field:NotBlank @field:Size(max = 100) val title: String,
        @JsonProperty("alias") @field:Size(max = 100) val alias: String?,
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PutMapping("/courses/{courseId}")
    fun controller(@PathVariable("courseId") courseIdStr: String, @Valid @RequestBody dto: Req, caller: EasyUser) {
        log.debug { "Update course $courseIdStr by ${caller.id}" }

        val courseId = courseIdStr.idToLongOrInvalidReq()
        caller.assertAccess {
            teacherOnCourse(courseId, false)
        }

        // Update title only if admin
        putCourse(courseId, dto, caller.isAdmin())
    }

    private fun putCourse(courseId: Long, dto: Req, isAdmin: Boolean) = transaction {
        Course.update({ Course.id.eq(courseId) }) {
            if (isAdmin)
                it[title] = dto.title
            it[alias] = dto.alias
        }
    }
}