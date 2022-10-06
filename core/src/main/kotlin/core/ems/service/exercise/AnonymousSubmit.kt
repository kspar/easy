package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.aas.AutoGradeScheduler
import core.db.AnonymousSubmission
import core.db.Exercise
import core.db.ExerciseVer
import core.db.PriorityLevel
import core.ems.service.assertExerciseIsAutoGradable
import core.ems.service.assertUnauthAccessToExercise
import core.ems.service.idToLongOrInvalidReq
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import javax.validation.constraints.Size
import kotlin.coroutines.EmptyCoroutineContext

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class AnonymousSubmitCont(private val autoGradeScheduler: AutoGradeScheduler) {

    data class Req(@JsonProperty("solution", required = true) @field:Size(max = 300000) val solution: String)

    data class Resp(
        @JsonProperty("grade", required = true) val grade: Int,
        @JsonProperty("feedback", required = true) val feedback: String
    )


    @PostMapping("/unauth/exercises/{exerciseId}/anonymous/autoassess")
    fun controller(@PathVariable("exerciseId") exerciseIdStr: String, @Valid @RequestBody solutionBody: Req): Resp {
        log.debug { "Autograding anonymous submission of exercise $exerciseIdStr" }
        val exerciseId = exerciseIdStr.idToLongOrInvalidReq()

        assertUnauthAccessToExercise(exerciseId)
        assertExerciseIsAutoGradable(exerciseId)

        val grade = autoGradeSolution(exerciseId, solutionBody.solution, autoGradeScheduler)
        insertAnonymousSubmission(exerciseId, solutionBody.solution, grade.grade, grade.feedback)
        return Resp(grade.grade, grade.feedback)
    }

    private fun autoGradeSolution(courseExId: Long, solution: String, autoGradeScheduler: AutoGradeScheduler) =
        runBlocking {
            autoGradeScheduler.submitAndAwait(
                selectAutoExIdOrIllegalStateException(courseExId),
                solution,
                PriorityLevel.ANONYMOUS
            )
        }

    private fun insertAnonymousSubmission(exerciseId: Long, solution: String, grade: Int, feedback: String) {
        // TODO: delete the oldest submission from that table if required (max saved submissions is in conf - 50 for now), update statistics
        CoroutineScope(EmptyCoroutineContext).launch {
            transaction {
                AnonymousSubmission.insert {
                    it[AnonymousSubmission.exercise] = exerciseId
                    it[AnonymousSubmission.createdAt] = DateTime.now()
                    it[AnonymousSubmission.solution] = solution
                    it[AnonymousSubmission.grade] = grade
                    it[AnonymousSubmission.feedback] = feedback
                }

                val deleteAfter = AnonymousSubmission.select {
                    AnonymousSubmission.exercise eq exerciseId
                }
                    .orderBy(
                        AnonymousSubmission.createdAt,
                        SortOrder.DESC
                    ) // TODO: first must be latest, last is oldest
                    .limit(50) // TODO: conf
                    .map {
                        it[AnonymousSubmission.createdAt]
                    }
                    .lastOrNull()

                if (deleteAfter != null)
                    AnonymousSubmission.deleteWhere {
                        AnonymousSubmission.exercise.eq(exerciseId) and
                                AnonymousSubmission.createdAt.less(deleteAfter)
                    }
            }
        }
    }

    private fun selectAutoExIdOrIllegalStateException(exerciseId: Long): Long = transaction {
        (Exercise innerJoin ExerciseVer).slice(ExerciseVer.autoExerciseId)
            .select { Exercise.id eq exerciseId and ExerciseVer.validTo.isNull() }
            .map { it[ExerciseVer.autoExerciseId] }.single()?.value
            ?: throw IllegalStateException("Exercise grader type is AUTO but auto exercise id is null")
    }
}
