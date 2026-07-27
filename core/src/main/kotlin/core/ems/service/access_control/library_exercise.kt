package core.ems.service.access_control

import core.conf.security.EasyUser
import core.db.DirAccessLevel
import core.db.Exercise
import core.db.ExerciseVer
import core.db.SolutionFileType
import core.ems.service.getImplicitDirFromExercise
import core.ems.service.hasAccountDirAccess
import core.exception.ForbiddenException
import core.exception.InvalidRequestException
import core.exception.ReqError
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

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
        Exercise.select(Exercise.anonymousAutoassessEnabled)
            .where { Exercise.id.eq(exerciseId) }
            .map { it[Exercise.anonymousAutoassessEnabled] }
            .singleOrNull()
    }

    if (unauthEnabled != true) {
        throw ForbiddenException("No unauthenticated access to exercise $exerciseId", ReqError.NO_EXERCISE_ACCESS)
    }
}

fun assertExerciseHasTextEditorSubmission(exerciseId: Long) {
    val solutionType = transaction {
        (Exercise innerJoin ExerciseVer)
            .selectAll().where { Exercise.id.eq(exerciseId) and ExerciseVer.validTo.isNull() }
            .map { it[ExerciseVer.solutionFileType] }
            .singleOrNull() ?: throw ForbiddenException(
            "No access to exercise $exerciseId", ReqError.NO_EXERCISE_ACCESS
        )
    }

    if (solutionType != SolutionFileType.TEXT_EDITOR) {
        throw InvalidRequestException(
            "Exercise $exerciseId has a solution type other than TEXT_EDITOR", ReqError.EXERCISE_WRONG_SOLUTION_TYPE
        )
    }
}
