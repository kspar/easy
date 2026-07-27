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
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v2")
class DeleteExercise {
    private val log = KotlinLogging.logger {}

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
        val autoExIds = ExerciseVer.selectAll().where { ExerciseVer.exercise eq exId }.mapNotNull {
            it[ExerciseVer.autoExerciseId]?.value
        }

        ExerciseVer.deleteWhere { ExerciseVer.exercise eq exId }

        Asset.deleteWhere { Asset.autoExercise.inList(autoExIds) }
        AutoExercise.deleteWhere { AutoExercise.id.inList(autoExIds) }
    }
}