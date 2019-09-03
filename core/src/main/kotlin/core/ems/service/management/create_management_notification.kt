package core.ems.service.management

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.ManagementNotification
import mu.KotlinLogging
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class CreateManagementNotificationsController {

    data class Req(@JsonProperty("message", required = true) val message: String)

    @Secured("ROLE_ADMIN")
    @PostMapping("/management/notifications")
    fun controller(@RequestBody dto: Req, caller: EasyUser) {

        log.debug { "${caller.id} is creating new system management notification: $dto" }

        insertMessage(dto)
    }
}

private fun insertMessage(dto: CreateManagementNotificationsController.Req) {
    transaction {
        ManagementNotification.insert {
            it[message] = dto.message
        }
    }
}
