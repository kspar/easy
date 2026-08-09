package core.ems.service.management

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.exception.InvalidRequestException
import core.db.ManagementNotification
import org.joda.time.DateTime
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v2")
class AdminCreateManagementNotificationsController {
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
    @PostMapping("/management/notifications")
    fun controller(@Valid @RequestBody dto: Req, caller: EasyUser) {

        log.info { "${caller.id} is creating new system management notification: $dto" }

        insertMessage(dto)
    }

    private fun insertMessage(dto: Req) {
        transaction {
            ManagementNotification.insert {
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

