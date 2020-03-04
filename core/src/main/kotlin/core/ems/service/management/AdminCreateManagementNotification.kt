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
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Size

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class AdminCreateManagementNotificationsController {

    data class Req(@JsonProperty("message", required = true)
                   @field:NotBlank @field:Size(max = 1000) val message: String)

    @Secured("ROLE_ADMIN")
    @PostMapping("/management/notifications")
    fun controller(@Valid @RequestBody dto: Req, caller: EasyUser) {

        log.debug { "${caller.id} is creating new system management notification: $dto" }

        insertMessage(dto)
    }
}

private fun insertMessage(dto: AdminCreateManagementNotificationsController.Req) {
    transaction {
        ManagementNotification.insert {
            it[message] = dto.message
        }
    }
}
