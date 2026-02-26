package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.aas.AutoGradeScheduler
import core.db.*
import core.ems.service.access_control.assertExerciseHasTextEditorSubmission
import core.ems.service.access_control.assertUnauthAccessToExercise
import core.ems.service.assertExerciseIsAutoGradable
import core.ems.service.idToLongOrInvalidReq
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.joda.time.DateTime
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.*
import kotlin.coroutines.EmptyCoroutineContext

@RestController
@RequestMapping("/v2")
class AnonymousSubmitCont(private val autoGradeScheduler: AutoGradeScheduler) {
    private val log = KotlinLogging.logger {}

    @Value("\${easy.core.auto-assess.anonymous-submissions-to-keep}")
    private lateinit var submissionToKeep: String

    // TODO should be required = true?
    data class Req(
        @param:JsonProperty("solution") @field:Size(max = 300000) val solution: String
    )

    data class Resp(
        @get:JsonProperty("grade") val grade: Int,
        @get:JsonProperty("feedback") val feedback: String
    )


    @PostMapping("/unauth/exercises/{exerciseId}/anonymous/autoassess")
    fun controller(@PathVariable("exerciseId") exerciseIdStr: String, @Valid @RequestBody solutionBody: Req): Resp {

        log.info { "Anonymous submission to exercise $exerciseIdStr" }
        val exerciseId = exerciseIdStr.idToLongOrInvalidReq()

        assertUnauthAccessToExercise(exerciseId)
        assertExerciseHasTextEditorSubmission(exerciseId)
        assertExerciseIsAutoGradable(exerciseId)

        val grade = autoGradeSolution(exerciseId, solutionBody.solution, autoGradeScheduler)
        insertAnonymousSubmission(exerciseId, solutionBody.solution, grade.grade, grade.feedback)
        return Resp(grade.grade, grade.feedback)
    }

    private fun autoGradeSolution(courseExId: Long, solution: String, autoGradeScheduler: AutoGradeScheduler) =
        runBlocking {
            autoGradeScheduler.submitAndAwait(
                selectAutoExIdOrIllegalStateException(courseExId), solution, PriorityLevel.ANONYMOUS
            )
        }

    private fun insertAnonymousSubmission(exerciseId: Long, solution: String, grade: Int, feedback: String) {
        CoroutineScope(EmptyCoroutineContext).launch {
            transaction {
                val time = DateTime.now()

                AnonymousSubmission.insert {
                    it[AnonymousSubmission.exercise] = exerciseId
                    it[AnonymousSubmission.createdAt] = time
                    it[AnonymousSubmission.solution] = solution
                    it[AnonymousSubmission.grade] = grade
                    it[AnonymousSubmission.feedback] = feedback
                }

                StatsAnonymousSubmission.insert {
                    it[StatsAnonymousSubmission.exercise] = exerciseId
                    it[StatsAnonymousSubmission.createdAt] = time
                    it[StatsAnonymousSubmission.solutionLength] = solution.length
                    it[StatsAnonymousSubmission.points] = grade
                }

                val deleteAfter =
                    AnonymousSubmission.selectAll().where { AnonymousSubmission.exercise eq exerciseId }.orderBy(
                        AnonymousSubmission.createdAt, SortOrder.DESC
                    ).limit(submissionToKeep.toInt()).map {
                        it[AnonymousSubmission.createdAt]
                    }.last()

                AnonymousSubmission.deleteWhere {
                    AnonymousSubmission.exercise.eq(exerciseId) and AnonymousSubmission.createdAt.less(deleteAfter)
                }
            }
        }
    }

    private fun selectAutoExIdOrIllegalStateException(exerciseId: Long): Long = transaction {
        (Exercise innerJoin ExerciseVer).select(ExerciseVer.autoExerciseId)
            .where { Exercise.id eq exerciseId and ExerciseVer.validTo.isNull() }
            .map { it[ExerciseVer.autoExerciseId] }
            .single()?.value ?: throw IllegalStateException("Exercise grader type is AUTO but auto exercise id is null")
    }
}


