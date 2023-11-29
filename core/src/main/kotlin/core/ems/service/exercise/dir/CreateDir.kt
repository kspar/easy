package core.ems.service.exercise.dir

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Dir
import core.db.DirAccessLevel
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.libraryDir
import core.ems.service.getImplicitGroupFromAccount
import core.ems.service.idToLongOrInvalidReq
import core.ems.service.upsertGroupDirAccess
import mu.KotlinLogging
import org.jetbrains.exposed.dao.id.EntityID
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
class CreateDirController {
    private val log = KotlinLogging.logger {}

    data class Req(
        @JsonProperty("name") @field:NotBlank @field:Size(max = 100) val name: String,
        @JsonProperty("parent_dir_id") @field:Size(max = 100) val parentId: String?,
    )

    data class Resp(@JsonProperty("id") val id: String)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/lib/dirs")
    fun controller(@Valid @RequestBody body: Req, caller: EasyUser): Resp {
        log.info { "Creating lib dir ${body.name} in dir ${body.parentId} by ${caller.id}" }

        val parentId = body.parentId?.idToLongOrInvalidReq()

        caller.assertAccess {
            if (parentId != null)
                libraryDir(parentId, DirAccessLevel.PRA)
        }

        return Resp(insertDir(body.name, parentId, caller).toString())
    }

    private fun insertDir(newDirName: String, parentDirId: Long?, caller: EasyUser): Long = transaction {
        val now = DateTime.now()

        val newDirId = Dir.insertAndGetId {
            it[name] = newDirName
            if (parentDirId != null) {
                it[parentDir] = EntityID(parentDirId, Dir)
            }
            it[createdAt] = now
            it[modifiedAt] = now
        }.value

        upsertGroupDirAccess(getImplicitGroupFromAccount(caller.id), newDirId, DirAccessLevel.PRAWM)

        newDirId
    }
}
