package core.ems.service.group

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Account
import core.db.AccountGroup
import core.db.Group
import mu.KotlinLogging
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Size


@RestController
@RequestMapping("/v2")
class CreateGroupController {
    private val log = KotlinLogging.logger {}

    data class Req(
        @JsonProperty("name") @field:NotBlank @field:Size(max = 100) val name: String,
        @JsonProperty("color") @field:Size(min = 1, max = 100) val color: String?,
    )

    data class Resp(@JsonProperty("id") val id: String)

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
