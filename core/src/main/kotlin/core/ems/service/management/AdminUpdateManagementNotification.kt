package core.ems.service.management

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.ManagementNotification
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import mu.KotlinLogging
import org.jetbrains.exposed.sql.select
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
class AdminUpdateManagementNotificationsController {

    data class Req(@JsonProperty("message", required = true)
                   @field:NotBlank @field:Size(max = 1000) val message: String)

    @Secured("ROLE_ADMIN")
    @PatchMapping("/management/notifications/{notificationId}")
    fun controller(@PathVariable("notificationId") notificationIdStr: String,
                   @Valid @RequestBody dto: Req, caller: EasyUser) {

        val notificationId = notificationIdStr.idToLongOrInvalidReq()

        log.debug { "${caller.id} requests updating system management notification with ID $notificationId with $dto" }

        updateMessage(dto, notificationId)
    }
}

private fun updateMessage(dto: AdminUpdateManagementNotificationsController.Req, notificationId: Long) {
    transaction {

        val messageExists = ManagementNotification.select { ManagementNotification.id eq notificationId }.count() == 1L

        if (!messageExists) {
            throw InvalidRequestException("No message with ID $notificationId found.")
        }

        ManagementNotification.update({ ManagementNotification.id eq notificationId }) {
            it[message] = dto.message
        }
    }
}