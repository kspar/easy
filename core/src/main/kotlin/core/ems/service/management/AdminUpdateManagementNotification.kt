package core.ems.service.management

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.ManagementNotification
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
        @field:NotBlank @field:Size(max = 1000) val message: String
    )

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
            }
        }
    }
}

