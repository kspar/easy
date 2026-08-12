package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.annotation.JsonSerialize
import core.aas.AutoGradeScheduler
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.exerciseViaCourse
import core.ems.service.access_control.libraryExercise
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import core.exception.ReqError
import core.util.DateTimeSerializer
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import kotlinx.coroutines.runBlocking
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*


@RestController
@RequestMapping("/v2")
class TeacherAutoassess(val autoGradeScheduler: AutoGradeScheduler) {
    private val log = KotlinLogging.logger {}

    data class Req(
        @param:JsonProperty("solution") @field:Size(max = 300000) val solution: String
    )

    data class Resp(
        @get:JsonProperty("grade") val grade: Int,
        @get:JsonProperty("feedback") val feedback: String?,
        @get:JsonSerialize(using = DateTimeSerializer::class)
        @get:JsonProperty("timestamp") val timestamp: DateTime,
    )


    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/exercises/{exerciseId}/testing/autoassess")
    fun controller(
        @PathVariable("exerciseId") exerciseIdStr: String,
        @RequestParam("course", required = false) courseIdStr: String?,
        @Valid @RequestBody dto: Req,
        caller: EasyUser
    ): Resp {
        log.info { "Teacher/admin ${caller.id} autoassessing solution to exercise $exerciseIdStr" }
        val exerciseId = exerciseIdStr.idToLongOrInvalidReq()
        val courseId = courseIdStr?.idToLongOrInvalidReq()

        caller.assertAccess {
            // Can access through course or directly via library
            if (courseId != null)
                exerciseViaCourse(exerciseId, courseId)
            else
                libraryExercise(exerciseId, DirAccessLevel.PR)
        }

        val (submissionId, submissionTime) = insertTeacherSubmission(exerciseId, dto.solution, caller.id)

        val aaId = getAutoExerciseId(exerciseId)
            ?: throw InvalidRequestException(
                "Autoassessment not found for exercise $exerciseIdStr",
                ReqError.EXERCISE_NOT_AUTOASSESSABLE
            )

        val aaResult = runBlocking {
            autoGradeScheduler.submitAndAwait(aaId, dto.solution, PriorityLevel.AUTHENTICATED)
        }

        // Recorded against the row inserted above rather than inserted with it, because the row has
        // to exist before this line: if grading throws, the teacher still has the solution in their
        // history to retry. The result is therefore an update, and a submission with no result is a
        // normal state rather than a broken one.
        saveResult(submissionId, aaResult.grade, aaResult.feedback)

        return Resp(aaResult.grade, aaResult.feedback, submissionTime)
    }

    private fun getAutoExerciseId(exerciseId: Long): Long? = transaction {
        (Exercise innerJoin ExerciseVer)
            .select(ExerciseVer.autoExerciseId)
            .where { Exercise.id eq exerciseId and ExerciseVer.validTo.isNull() }
            .map { it[ExerciseVer.autoExerciseId] }
            .single()?.value
    }

    private fun insertTeacherSubmission(exerciseId: Long, solution: String, teacherId: String): Pair<Long, DateTime> =
        transaction {
            val now = DateTime.now()

            val id = TeacherSubmission.insertAndGetId {
                it[TeacherSubmission.solution] = solution
                it[createdAt] = now
                it[exercise] = exerciseId
                it[teacher] = teacherId
            }
            id.value to now
        }

    private fun saveResult(submissionId: Long, grade: Int, feedback: String?) = transaction {
        TeacherSubmission.update({ TeacherSubmission.id eq submissionId }) {
            it[TeacherSubmission.grade] = grade
            it[TeacherSubmission.feedback] = feedback
        }
    }
}
