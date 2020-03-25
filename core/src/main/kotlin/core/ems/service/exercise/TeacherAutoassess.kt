package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.aas.autoAssess
import core.conf.security.EasyUser
import core.db.Exercise
import core.db.ExerciseVer
import core.db.Teacher
import core.db.TeacherSubmission
import core.ems.service.assertTeacherOrAdminHasAccessToExercise
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import core.exception.ReqError
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
class TeacherAutoassController {

    data class Req(@JsonProperty("solution") @field:Size(max = 300000) val solution: String)

    data class Resp(@JsonProperty("grade") val grade: Int,
                    @JsonProperty("feedback") val feedback: String?)


    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/exercises/{exerciseId}/testing/autoassess")
    fun controller(@PathVariable("exerciseId") exerciseIdStr: String,
                   @Valid @RequestBody dto: Req,
                   caller: EasyUser): Resp {

        val callerId = caller.id
        log.debug { "Teacher/admin $callerId autoassessing solution to exercise $exerciseIdStr" }
        val exerciseId = exerciseIdStr.idToLongOrInvalidReq()

        assertTeacherOrAdminHasAccessToExercise(caller, exerciseId)

        insertTeacherSubmission(exerciseId, dto.solution, callerId)

        val aaId = getAutoExerciseId(exerciseId)
                ?: throw InvalidRequestException("Autoassessment not found for exercise $exerciseIdStr", ReqError.EXERCISE_NOT_AUTOASSESSABLE)

        val aaResult = autoAssess(aaId, dto.solution)
        return Resp(aaResult.grade, aaResult.feedback)
    }
}


private fun getAutoExerciseId(exerciseId: Long): EntityID<Long>? {
    return transaction {
        (Exercise innerJoin ExerciseVer)
                .slice(ExerciseVer.autoExerciseId)
                .select { Exercise.id eq exerciseId and ExerciseVer.validTo.isNull() }
                .map { it[ExerciseVer.autoExerciseId] }
                .single()
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
