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
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.dao.id.EntityID
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
class CreateDirController {
    private val log = KotlinLogging.logger {}

    data class Req(
        @param:JsonProperty("name") @field:NotBlank @field:Size(max = 100) val name: String,
        @param:JsonProperty("parent_dir_id") @field:Size(max = 100) val parentId: String?,
    )

    data class Resp(@get:JsonProperty("id") val id: String)

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
