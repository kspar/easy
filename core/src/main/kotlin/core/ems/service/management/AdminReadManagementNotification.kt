package core.ems.service.management

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.ManagementNotification
import org.joda.time.DateTime
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
class AdminReadManagementNotificationsController {
    private val log = KotlinLogging.logger {}

    data class Resp(
        @get:JsonProperty("messages")
        @get:JsonInclude(JsonInclude.Include.NON_NULL) val messages: List<MessageResp>
    )

    data class MessageResp(
        @get:JsonProperty("id") val messageId: String,
        @get:JsonProperty("message") val message: String,
        @get:JsonProperty("severity") val severity: String,
        @get:JsonProperty("link_url") val linkUrl: String?,
        @get:JsonProperty("link_label") val linkLabel: String?,
        @get:JsonProperty("visible_from") val visibleFrom: DateTime?,
        @get:JsonProperty("visible_until") val visibleUntil: DateTime?,
        @get:JsonProperty("for_students") val forStudents: Boolean,
        @get:JsonProperty("for_teachers") val forTeachers: Boolean,
        @get:JsonProperty("for_admins") val forAdmins: Boolean,
    )

    @Secured("ROLE_ADMIN")
    @GetMapping("/management/notifications")
    fun controller(caller: EasyUser): Resp {

        log.info { "Getting system management notifications for ${caller.id}" }

        return selectMessages()
    }

    private fun selectMessages(): Resp = transaction {
        Resp(
            ManagementNotification
                .selectAll()
                .orderBy(ManagementNotification.id, SortOrder.DESC)
                .map {
                    MessageResp(
                        it[ManagementNotification.id].value.toString(),
                        it[ManagementNotification.message],
                        it[ManagementNotification.severity],
                        it[ManagementNotification.linkUrl],
                        it[ManagementNotification.linkLabel],
                        it[ManagementNotification.visibleFrom],
                        it[ManagementNotification.visibleUntil],
                        it[ManagementNotification.forStudents],
                        it[ManagementNotification.forTeachers],
                        it[ManagementNotification.forAdmins],
                    )
                })
    }
}

