package core.ems.service.management

import core.conf.security.EasyUser
import core.db.ManagementNotification
import core.ems.service.idToLongOrInvalidReq
import mu.KotlinLogging
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class DeleteManagementNotification {

    @Secured("ROLE_ADMIN")
    @DeleteMapping("/management/notifications/{notificationId}")
    fun controller(@PathVariable("notificationId") notificationIdStr: String, caller: EasyUser) {

        val notificationId = notificationIdStr.idToLongOrInvalidReq()

        log.debug { "${caller.id} is deleting system management notification $notificationId" }

        deleteMessage(notificationId)
    }
}

private fun deleteMessage(notificationId: Long) {
    transaction {
        ManagementNotification.deleteWhere { ManagementNotification.id eq notificationId }
    }
}