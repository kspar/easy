package core.ems.service.exercise.dir

import core.conf.security.EasyUser
import core.db.Dir
import core.db.DirAccessLevel
import core.ems.service.*
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.libraryDir
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class DeleteDir {

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @DeleteMapping("/lib/dirs/{dirId}")
    fun controller(
        @PathVariable("dirId") dirIdString: String,
        caller: EasyUser
    ) {
        log.debug { "Deleting lib dir $dirIdString by ${caller.id}" }

        val dirId = dirIdString.idToLongOrInvalidReq()

        caller.assertAccess {
            libraryDir(dirId, DirAccessLevel.PRAWM)
        }

        assertDirExists(dirId, allowImplicit = false)
        assertDirIsEmpty(dirId)

        deleteDir(dirId)
    }

    fun deleteDir(dirId: Long) = transaction {
        // Remove accesses
        getDirectGroupDirAccesses(dirId).groupAccesses.forEach {
            libraryDirRemoveAccess(dirId, it.id)
        }
        // Remove any access
        libraryDirRemoveAccess(dirId, null)

        // Delete dir
        Dir.deleteWhere { Dir.id eq dirId and Dir.isImplicit.eq(false) }
    }
}