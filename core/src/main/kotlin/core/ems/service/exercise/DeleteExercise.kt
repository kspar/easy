package core.ems.service.exercise

import core.conf.security.EasyUser
import core.db.*
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.assertExerciseIsNotOnAnyCourse
import core.ems.service.access_control.libraryExercise
import core.ems.service.getDirectGroupDirAccesses
import core.ems.service.getImplicitDirFromExercise
import core.ems.service.idToLongOrInvalidReq
import core.ems.service.libraryDirRemoveAccess
import mu.KotlinLogging
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class DeleteExercise {

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @DeleteMapping("/exercises/{exerciseId}")
    fun controller(
        @PathVariable("exerciseId") exIdString: String,
        caller: EasyUser
    ) {
        log.debug { "Deleting exercise $exIdString by ${caller.id}" }

        val exId = exIdString.idToLongOrInvalidReq()

        caller.assertAccess {
            libraryExercise(exId, DirAccessLevel.PRAWM)
        }

        assertExerciseIsNotOnAnyCourse(exId)
        deleteExercise(exId)
    }

    private fun deleteExercise(exId: Long) = transaction {
        // Delete related stuff
        TeacherSubmission.deleteWhere { TeacherSubmission.exercise eq exId }
        StoredFile.deleteWhere { StoredFile.exercise eq exId }
        AnonymousSubmission.deleteWhere { AnonymousSubmission.exercise eq exId }

        // Delete exercise
        deleteVersions(exId)
        Exercise.deleteWhere { Exercise.id eq exId }

        // Remove accesses
        val implicitDirId = getImplicitDirFromExercise(exId)
        getDirectGroupDirAccesses(implicitDirId).groupAccesses.forEach {
            libraryDirRemoveAccess(implicitDirId, it.id)
        }
        // Remove any access
        libraryDirRemoveAccess(implicitDirId, null)

        // Delete implicit dir
        Dir.deleteWhere { id eq implicitDirId and isImplicit }
    }

    private fun deleteVersions(exId: Long) {
        // Delete auto exercises
        val autoExIds = ExerciseVer.selectAll().where { ExerciseVer.exercise eq exId }.mapNotNull {
            it[ExerciseVer.autoExerciseId]?.value
        }

        Asset.deleteWhere { Asset.autoExercise.inList(autoExIds) }
        AutoExercise.deleteWhere { AutoExercise.id.inList(autoExIds) }

        // Delete versions
        ExerciseVer.deleteWhere { ExerciseVer.exercise eq exId }
    }
}