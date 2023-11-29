package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
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
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import javax.validation.constraints.Size


@RestController
@RequestMapping("/v2")
class TeacherAutoassess(val autoGradeScheduler: AutoGradeScheduler) {
    private val log = KotlinLogging.logger {}

    data class Req(
        @JsonProperty("solution") @field:Size(max = 300000) val solution: String
    )

    data class Resp(
        @JsonProperty("grade") val grade: Int,
        @JsonProperty("feedback") val feedback: String?,
        @JsonSerialize(using = DateTimeSerializer::class)
        @JsonProperty("timestamp") val timestamp: DateTime,
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

        val submissionTime = insertTeacherSubmission(exerciseId, dto.solution, caller.id)

        val aaId = getAutoExerciseId(exerciseId)
            ?: throw InvalidRequestException(
                "Autoassessment not found for exercise $exerciseIdStr",
                ReqError.EXERCISE_NOT_AUTOASSESSABLE
            )

        val aaResult = runBlocking {
            autoGradeScheduler.submitAndAwait(aaId, dto.solution, PriorityLevel.AUTHENTICATED)
        }
        // TODO: maybe should save auto assessments as well and follow the scheme as with student submit:
        // this service submits and returns
        // and another service .../await will wait for the assessment to finish and return the result

        return Resp(aaResult.grade, aaResult.feedback, submissionTime)
    }

    private fun getAutoExerciseId(exerciseId: Long): Long? {
        return transaction {
            (Exercise innerJoin ExerciseVer)
                .slice(ExerciseVer.autoExerciseId)
                .select { Exercise.id eq exerciseId and ExerciseVer.validTo.isNull() }
                .map { it[ExerciseVer.autoExerciseId] }
                .single()?.value
        }
    }

    private fun insertTeacherSubmission(exerciseId: Long, solution: String, teacherId: String): DateTime {
        val now = DateTime.now()
        transaction {
            TeacherSubmission.insert {
                it[TeacherSubmission.solution] = solution
                it[createdAt] = now
                it[exercise] = EntityID(exerciseId, TeacherSubmission)
                it[teacher] = EntityID(teacherId, Teacher)
            }
        }
        return now
    }
}
