package core.ems.service.management

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.ManagementNotification
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v2")
class CommonReadManagementNotificationsController {
    private val log = KotlinLogging.logger {}

    data class Resp(
        @get:JsonProperty("messages")
        @get:JsonInclude(JsonInclude.Include.NON_NULL) val messages: List<MessageResp>
    )

    data class MessageResp(@get:JsonProperty("message") val message: String)

    @Secured("ROLE_ADMIN", "ROLE_TEACHER", "ROLE_STUDENT")
    @GetMapping("/management/common/notifications")
    fun controller(caller: EasyUser): Resp {

        log.info { "Getting common system management notifications for ${caller.id}" }

        return selectMessages()
    }

    private fun selectMessages(): Resp = transaction {
        Resp(
            ManagementNotification.selectAll()
                .orderBy(ManagementNotification.id, SortOrder.DESC)
                .map {
                    MessageResp(
                        it[ManagementNotification.message]
                    )
                })
    }

}



