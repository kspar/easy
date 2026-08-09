package core.ems.service.management

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.ManagementNotification
import org.joda.time.DateTime
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*


@RestController
@RequestMapping("/v2")
class AdminUpdateManagementNotificationsController {
    private val log = KotlinLogging.logger {}

    data class Req(
        @param:JsonProperty("message", required = true)
        @field:NotBlank @field:Size(max = 1000) val message: String,

        // Defaulted so an existing caller sending only `message` keeps working: the pre-scheduling
        // API was exactly that, and there is no reason to break it to add optional capability.
        @param:JsonProperty("severity") val severity: String = "INFO",
        @param:JsonProperty("link_url") @field:Size(max = 2000) val linkUrl: String? = null,
        @param:JsonProperty("link_label") @field:Size(max = 100) val linkLabel: String? = null,
        @param:JsonProperty("visible_from") val visibleFrom: DateTime? = null,
        @param:JsonProperty("visible_until") val visibleUntil: DateTime? = null,
        @param:JsonProperty("for_students") val forStudents: Boolean = true,
        @param:JsonProperty("for_teachers") val forTeachers: Boolean = true,
        @param:JsonProperty("for_admins") val forAdmins: Boolean = true,
    ) {
        /**
         * Rejected here rather than stored and puzzled over later. An unknown severity would sort
         * last and render as a plain banner, which is a silent downgrade of something an admin
         * meant to be urgent.
         */
        fun validSeverityOrThrow(): String {
            val s = severity.uppercase()
            if (s !in setOf("URGENT", "INFO"))
                throw InvalidRequestException("severity must be URGENT or INFO, got '$severity'")
            if (visibleFrom != null && visibleUntil != null && !visibleUntil.isAfter(visibleFrom))
                throw InvalidRequestException("visible_until must be after visible_from")
            // A link with no label renders as a button with no text; a label with no link is a
            // promise the banner cannot keep. Both halves or neither.
            if ((linkUrl == null) != (linkLabel == null))
                throw InvalidRequestException("link_url and link_label must be given together")
            return s
        }
    }

    @Secured("ROLE_ADMIN")
    @PatchMapping("/management/notifications/{notificationId}")
    fun controller(
        @PathVariable("notificationId") notificationIdStr: String,
        @Valid @RequestBody dto: Req, caller: EasyUser
    ) {

        val notificationId = notificationIdStr.idToLongOrInvalidReq()

        log.info { "${caller.id} requests updating system management notification with ID $notificationId with $dto" }

        updateMessage(dto, notificationId)
    }

    private fun updateMessage(dto: Req, notificationId: Long) {
        transaction {

            val messageExists =
                ManagementNotification.selectAll().where { ManagementNotification.id eq notificationId }.count() == 1L

            if (!messageExists) {
                throw InvalidRequestException("No message with ID $notificationId found.")
            }

            ManagementNotification.update({ ManagementNotification.id eq notificationId }) {
                it[message] = dto.message
                it[severity] = dto.validSeverityOrThrow()
                it[linkUrl] = dto.linkUrl
                it[linkLabel] = dto.linkLabel
                it[visibleFrom] = dto.visibleFrom
                it[visibleUntil] = dto.visibleUntil
                it[forStudents] = dto.forStudents
                it[forTeachers] = dto.forTeachers
                it[forAdmins] = dto.forAdmins
            }
        }
    }
}

