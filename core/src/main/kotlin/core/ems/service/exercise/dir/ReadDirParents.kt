package core.ems.service.exercise.dir

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Dir
import core.db.DirAccessLevel
import core.ems.service.assertAccountHasDirAccess
import core.ems.service.assertDirExists
import core.ems.service.idToLongOrInvalidReq
import mu.KotlinLogging
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val log = KotlinLogging.logger {}


@RestController
@RequestMapping("/v2")
class ReadDirParentsController {

    data class Resp(
        @JsonProperty("parents") val parents: List<ParentDirResp>,
    )

    data class ParentDirResp(
        @JsonProperty("id") val id: String,
        @JsonProperty("name") val name: String,
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/lib/dirs/{dirId}/parents")
    fun controller(
        @PathVariable("dirId") dirIdString: String,
        caller: EasyUser
    ): Resp {

        log.debug { "Read parents for dir $dirIdString by ${caller.id}" }

        val dirId = dirIdString.idToLongOrInvalidReq()
        assertAccountHasDirAccess(caller, dirId, DirAccessLevel.P)
        assertDirExists(dirId, true)

        return selectParents(dirId)
    }


    private fun selectParents(dirId: Long): Resp {
        val currentDir = selectDir(dirId)
        val parents = mutableListOf<ParentDir>()

        var parentDir = currentDir.parentId
        while (parentDir != null) {
            val dir = selectDir(parentDir)
            parents.add(dir)
            parentDir = dir.parentId
        }

        return Resp(
            parents.map {
                ParentDirResp(it.id.toString(), it.name)
            }
        )
    }

    private data class ParentDir(val id: Long, val name: String, val parentId: Long?)

    private fun selectDir(dirId: Long): ParentDir {
        return transaction {
            Dir.slice(Dir.name, Dir.parentDir)
                .select {
                    Dir.id eq dirId
                }.map {
                    ParentDir(
                        dirId,
                        it[Dir.name],
                        it[Dir.parentDir]?.value,
                    )
                }.single()
        }
    }
}
