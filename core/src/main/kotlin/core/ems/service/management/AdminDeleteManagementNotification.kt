package core.ems.service.management

import core.conf.security.EasyUser
import core.db.ManagementNotification
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import mu.KotlinLogging
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v2")
class AdminDeleteManagementNotification {
    private val log = KotlinLogging.logger {}

    @Secured("ROLE_ADMIN")
    @DeleteMapping("/management/notifications/{notificationId}")
    fun controller(@PathVariable("notificationId") notificationIdStr: String, caller: EasyUser) {

        val notificationId = notificationIdStr.idToLongOrInvalidReq()

        log.info { "${caller.id} is requests deleting system management notification with ID $notificationId" }

        deleteMessage(notificationId)
    }

    private fun deleteMessage(notificationId: Long) {
        transaction {

            val messageExists =
                ManagementNotification.selectAll().where { ManagementNotification.id eq notificationId }.count() == 1L

            if (!messageExists) {
                throw InvalidRequestException("No message with ID $notificationId found.")
            }
            ManagementNotification.deleteWhere { ManagementNotification.id eq notificationId }
        }
    }
}
