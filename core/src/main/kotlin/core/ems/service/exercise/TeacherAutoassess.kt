package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.aas.AutoGradeScheduler
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.*
import core.exception.InvalidRequestException
import core.exception.ReqError
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

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class TeacherAutoassController(val autoGradeScheduler: AutoGradeScheduler) {

    data class Req(@JsonProperty("solution") @field:Size(max = 300000) val solution: String)

    data class Resp(
        @JsonProperty("grade") val grade: Int,
        @JsonProperty("feedback") val feedback: String?
    )


    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/exercises/{exerciseId}/testing/autoassess")
    fun controller(
        @PathVariable("exerciseId") exerciseIdStr: String,
        @RequestParam("course", required = false) courseIdStr: String?,
        @Valid @RequestBody dto: Req,
        caller: EasyUser
    ): Resp {

        val callerId = caller.id
        log.debug { "Teacher/admin $callerId autoassessing solution to exercise $exerciseIdStr" }
        val exerciseId = exerciseIdStr.idToLongOrInvalidReq()
        val courseId = courseIdStr?.idToLongOrInvalidReq()

        // Can access through course or directly via library
        if (courseId != null) {
            assertTeacherOrAdminHasAccessToCourse(caller, courseId)
            assertExerciseIsOnCourse(exerciseId, courseId, false)
        } else {
            assertTeacherOrAdminHasAccessToExercise(caller, exerciseId)
        }

        insertTeacherSubmission(exerciseId, dto.solution, callerId)

        val aaId = getAutoExerciseId(exerciseId)
            ?: throw InvalidRequestException(
                "Autoassessment not found for exercise $exerciseIdStr",
                ReqError.EXERCISE_NOT_AUTOASSESSABLE
            )

        val aaResult = runBlocking {
            autoGradeScheduler.submitAndAwait(
                aaId,
                dto.solution,
                PriorityLevel.AUTHENTICATED
            )
        }
        // TODO: maybe should save auto assessments as well and follow the scheme as with student submit:
        // this service submits and returns
        // and another service .../await will wait for the assessment to finish and return the result

        return Resp(aaResult.grade, aaResult.feedback)
    }
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


private fun insertTeacherSubmission(exerciseId: Long, solution: String, teacherId: String) {
    transaction {
        TeacherSubmission.insert {
            it[TeacherSubmission.solution] = solution
            it[TeacherSubmission.createdAt] = DateTime.now()
            it[TeacherSubmission.exercise] = EntityID(exerciseId, TeacherSubmission)
            it[TeacherSubmission.teacher] = EntityID(teacherId, Teacher)
        }
    }
}
