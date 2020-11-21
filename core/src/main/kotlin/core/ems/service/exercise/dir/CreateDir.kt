package core.ems.service.exercise.dir

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Dir
import core.db.DirAccessLevel
import core.db.GroupDirAccess
import core.ems.service.assertAccountHasDirAccess
import core.ems.service.getAccountImplicitGroupId
import core.ems.service.hasAccountDirAccess
import core.ems.service.idToLongOrInvalidReq
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


private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class CreateDirController {
    data class Req(
            @JsonProperty("name", required = true) @field:NotBlank @field:Size(max = 100) val name: String,
            @JsonProperty("parent_dir_id", required = false) @field:Size(max = 100) val parentId: String?,
    )

    data class Resp(@JsonProperty("id") val id: String)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/lib/dirs")
    fun controller(@Valid @RequestBody body: Req, caller: EasyUser): Resp {
        log.debug { "Creating lib dir $body by ${caller.id}" }

        val parentId = body.parentId?.idToLongOrInvalidReq()

        if (parentId != null) {
            assertAccountHasDirAccess(caller, parentId, DirAccessLevel.PRA)
        }

        return Resp(insertDir(body.name, parentId, caller).toString())
    }
}

private fun insertDir(newDirName: String, parentDirId: Long?, caller: EasyUser): Long {
    return transaction {
        val now = DateTime.now()

        val newDirId = Dir.insertAndGetId {
            it[name] = newDirName
            if (parentDirId != null) {
                it[parentDir] = EntityID(parentDirId, Dir)
            }
            it[createdAt] = now
            it[modifiedAt] = now
        }

        // If caller doesn't have full access by inheritance, add it explicitly
        if (parentDirId == null || !hasAccountDirAccess(caller, parentDirId, DirAccessLevel.PRAWM)) {
            GroupDirAccess.insert {
                it[group] = getAccountImplicitGroupId(caller.id)
                it[dir] = newDirId
                it[level] = DirAccessLevel.PRAWM
                it[createdAt] = now
            }
        }

        newDirId.value
    }
}