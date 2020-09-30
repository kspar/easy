package core.ems.service.management

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.ManagementNotification
import mu.KotlinLogging
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class AdminReadManagementNotificationsController {

    data class Resp(@JsonProperty("messages")
                    @JsonInclude(JsonInclude.Include.NON_NULL) val messages: List<MessageResp>)

    data class MessageResp(@JsonProperty("id") val messageId: String,
                           @JsonProperty("message") val message: String)

    @Secured("ROLE_ADMIN")
    @GetMapping("/management/notifications")
    fun controller(caller: EasyUser): Resp {

        log.debug { "Getting system management notifications for ${caller.id}" }

        return selectMessages()
    }
}

private fun selectMessages(): AdminReadManagementNotificationsController.Resp {
    return transaction {
        AdminReadManagementNotificationsController.Resp(ManagementNotification.selectAll()
                .orderBy(ManagementNotification.id, SortOrder.DESC)
                .map {
                    AdminReadManagementNotificationsController.MessageResp(
                            it[ManagementNotification.id].value.toString(),
                            it[ManagementNotification.message]
                    )
                })
    }
}
