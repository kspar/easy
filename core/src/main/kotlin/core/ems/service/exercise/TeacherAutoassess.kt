package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.aas.service.autoAssess
import core.conf.security.EasyUser
import core.db.CourseExercise
import core.db.Exercise
import core.db.ExerciseVer
import core.ems.service.access.assertTeacherOrAdminHasAccessToCourse
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import mu.KotlinLogging
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class TeacherAutoassCont {

    data class Req(
            @JsonProperty("solution") val solution: String)

    data class Resp(
            @JsonProperty("grade") val grade: Int,
            @JsonProperty("feedback") val feedback: String?)


    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/teacher/courses/{courseId}/exercises/{courseExId}/autoassess")
    fun controller(@PathVariable("courseId") courseIdStr: String,
                   @PathVariable("courseExId") courseExIdStr: String,
                   @RequestBody dto: Req,
                   caller: EasyUser): Resp {

        val callerId = caller.id

        log.debug { "Teacher/admin $callerId autoassessing solution to exercise $courseExIdStr on course $courseIdStr" }

        val courseId = courseIdStr.idToLongOrInvalidReq()
        val courseExId = courseExIdStr.idToLongOrInvalidReq()

        assertTeacherOrAdminHasAccessToCourse(caller, courseId)

        val aaId = getAutoExerciseId(courseId, courseExId)
                ?: throw InvalidRequestException("Autoassessment not found for exercise $courseExId on course $courseId")

        val aaResult = autoAssess(aaId, dto.solution)
        return Resp(aaResult.grade, aaResult.feedback)
    }
}


private fun getAutoExerciseId(courseId: Long, courseExerciseId: Long): EntityID<Long>? {
    return transaction {
        (CourseExercise innerJoin Exercise innerJoin ExerciseVer)
                .slice(ExerciseVer.autoExerciseId)
                .select {
                    CourseExercise.course eq courseId and
                            (CourseExercise.id eq courseExerciseId) and
                            ExerciseVer.validTo.isNull()
                }
                .map { it[ExerciseVer.autoExerciseId] }
                .single()
    }
}
