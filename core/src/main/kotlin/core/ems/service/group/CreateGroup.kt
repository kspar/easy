package core.ems.service.group

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Account
import core.db.AccountGroup
import core.db.Group
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v2")
class CreateGroupController {
    private val log = KotlinLogging.logger {}

    data class Req(
        @param:JsonProperty("name") @field:NotBlank @field:Size(max = 100) val name: String,
        @param:JsonProperty("color") @field:Size(min = 1, max = 100) val color: String?,
    )

    data class Resp(@get:JsonProperty("id") val id: String)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/groups")
    fun controller(@Valid @RequestBody body: Req, caller: EasyUser): Resp {

        log.info { "Create group '${body.name}' (color: ${body.color}) by ${caller.id}" }

        val groupId = insertGroup(body, caller)
        return Resp(groupId.toString())
    }

    private fun insertGroup(newGroup: Req, caller: EasyUser): Long = transaction {
        val now = DateTime.now()

        val groupId = Group.insertAndGetId {
            it[name] = newGroup.name
            it[color] = newGroup.color
            it[isImplicit] = false
            it[createdAt] = now
        }

        // admins have full access to any group anyway
        if (!caller.isAdmin()) {
            AccountGroup.insert {
                it[account] = EntityID(caller.id, Account)
                it[group] = groupId
                it[isManager] = true
                it[createdAt] = now
            }
        }

        groupId.value
    }
}
