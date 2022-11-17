package core.ems.service.access_control

import core.conf.security.EasyUser
import core.db.DirAccessLevel
import core.db.Exercise
import core.ems.service.getImplicitDirFromExercise
import core.ems.service.hasAccountDirAccess
import core.exception.ForbiddenException
import core.exception.ReqError
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction

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


fun assertUnauthAccessToExercise(exerciseId: Long) {
    val unauthEnabled = transaction {
        Exercise.slice(Exercise.anonymousAutoassessEnabled)
            .select { Exercise.id.eq(exerciseId) }
            .map { it[Exercise.anonymousAutoassessEnabled] }
            .singleOrNull() ?: throw ForbiddenException(
            "No access to exercise $exerciseId", ReqError.NO_EXERCISE_ACCESS
        )
    }

    if (!unauthEnabled) {
        throw ForbiddenException("No access to exercise $exerciseId", ReqError.NO_EXERCISE_ACCESS)
    }
}
