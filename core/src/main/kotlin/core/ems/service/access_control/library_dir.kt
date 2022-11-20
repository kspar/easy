package core.ems.service.access_control

import core.conf.security.EasyUser
import core.db.DirAccessLevel
import core.ems.service.hasAccountDirAccess
import core.exception.ForbiddenException
import core.exception.ReqError

/**
 * Has the given access level to this directory.
 */
fun AccessChecksBuilder.libraryDir(dirId: Long, level: DirAccessLevel) = add { caller: EasyUser ->
    if (!hasAccountDirAccess(caller, dirId, level)) {
        throw ForbiddenException("User ${caller.id} does not have $level access to dir $dirId", ReqError.NO_DIR_ACCESS)
    }
}

