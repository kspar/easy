package core.ems.service.access_control

import core.conf.security.EasyUser
import core.db.DirAccessLevel
import core.ems.service.getImplicitDirFromExercise
import core.ems.service.hasAccountDirAccess
import core.exception.ForbiddenException
import core.exception.ReqError

/**
 * Has the given access level to this exercise in the exercise library.
 */
fun AccessChecksBuilder.libraryExercise(exerciseId: Long, accessLevel: DirAccessLevel) = add { caller: EasyUser ->
    val dirId = getImplicitDirFromExercise(exerciseId)
    if (!hasAccountDirAccess(caller, dirId, accessLevel)) {
        throw ForbiddenException(
            "User ${caller.id} does not have $accessLevel access to exercise $exerciseId",
            ReqError.NO_EXERCISE_ACCESS
        )
    }
}
