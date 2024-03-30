package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Course
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import core.exception.ReqError
import mu.KotlinLogging
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import javax.validation.constraints.NotBlank


@RestController
@RequestMapping("/v2")
class ArchiveCourse {
    private val log = KotlinLogging.logger {}

    data class Req(@JsonProperty("archived") @field:NotBlank val archivedStr: String)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/courses/{courseId}/archive")
    fun controller(@PathVariable("courseId") courseIdStr: String, @Valid @RequestBody dto: Req, caller: EasyUser) {
        log.info { "Set archive = ${dto.archivedStr} for course $courseIdStr by ${caller.id}" }

        val courseId = courseIdStr.idToLongOrInvalidReq()
        val archived = dto.archivedStr.toBooleanStrictOrNull()

        archived ?: throw InvalidRequestException(
            "'${dto.archivedStr}' cannot be interpreted as boolean",
            ReqError.INVALID_PARAMETER_VALUE
        )

        caller.assertAccess {
            teacherOnCourse(courseId)
        }

        archiveCourse(courseId, archived)
    }

    private fun archiveCourse(courseId: Long, archived: Boolean) = transaction {
        Course.update({ Course.id.eq(courseId) }) {
            it[Course.archived] = archived
        }
    }
}