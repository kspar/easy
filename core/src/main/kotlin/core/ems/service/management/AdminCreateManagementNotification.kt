package core.ems.service.management

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.ManagementNotification
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
        @field:NotBlank @field:Size(max = 1000) val message: String
    )

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
            }
        }
    }
}

