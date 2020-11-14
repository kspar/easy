package core.ems.service.group

import com.fasterxml.jackson.annotation.JsonProperty
import core.db.Group
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
    data class Req(@JsonProperty("name", required = true) @field:NotBlank @field:Size(max = 100) val name: String)

    data class Resp(@JsonProperty("id") val id: String)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/groups")
    fun controller(@Valid @RequestBody body: Req): Resp {
        val groupId = insertGroup(body)
        return Resp(groupId.toString())
    }
}

private fun insertGroup(newGroup: CreateGroupController.Req): Long {
    return transaction {
        Group.insertAndGetId {
            it[name] = newGroup.name
            it[color] = null
            it[isImplicit] = false
            it[createdAt] = DateTime.now()
        }.value
    }
}