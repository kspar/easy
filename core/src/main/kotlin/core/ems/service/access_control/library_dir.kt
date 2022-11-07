package core.ems.service.access_control

import core.conf.security.EasyUser
import core.db.Dir
import core.db.DirAccessLevel
import core.db.GroupDirAccess
import core.ems.service.assertDirExists
import core.ems.service.hasAccountDirAccess
import core.exception.ForbiddenException
import core.exception.ReqError
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime

/**
 * Has the given access level to this directory.
 */
fun AccessChecksBuilder.libraryDir(dirId: Long, level: DirAccessLevel) = add { caller: EasyUser ->
    if (!hasAccountDirAccess(caller, dirId, level)) {
        throw ForbiddenException("User ${caller.id} does not have $level access to dir $dirId", ReqError.NO_DIR_ACCESS)
    }
}


/**
 * Add given access level to given group for dir
 */
fun libraryDirAddAccess(dirId: Long, groupId: Long, level: DirAccessLevel) {
    transaction {
        assertDirExists(dirId)

        //Add given access to given group G.
        GroupDirAccess.insert {
            it[GroupDirAccess.group] = groupId
            it[GroupDirAccess.dir] = dirId
            it[GroupDirAccess.level] = level
            it[GroupDirAccess.createdAt] = DateTime.now()
        }

        // Look at parent dir D if it exists (if not, end).
        val parentDirId = Dir
            .slice(Dir.parentDir)
            .select { Dir.id eq dirId }
            .firstNotNullOfOrNull { it[Dir.parentDir]?.value } ?: return@transaction

        //  If G has at least P access to D, end.
        val parentDirAccessLevel = GroupDirAccess
            .slice(GroupDirAccess.level)
            .select {
                (GroupDirAccess.dir eq parentDirId) and (GroupDirAccess.group eq groupId)
            }.map { it[GroupDirAccess.level] }
            .firstOrNull()

        // Add P access for G to D -> repeat
        if (parentDirAccessLevel == null) {
            libraryDirAddAccess(parentDirId, groupId, DirAccessLevel.P)
        }
    }
}