package ee.urgas.ems.bl.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import ee.urgas.ems.bl.access.canTeacherAccessCourse
import ee.urgas.ems.bl.autoassess.AutoAssessResponse
import ee.urgas.ems.bl.autoassess.autoAssess
import ee.urgas.ems.conf.security.EasyUser
import ee.urgas.ems.db.CourseExercise
import ee.urgas.ems.db.Exercise
import ee.urgas.ems.db.ExerciseVer
import ee.urgas.ems.exception.ForbiddenException
import ee.urgas.ems.exception.InvalidRequestException
import mu.KotlinLogging
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class TeacherAutoassessController {

    @Value("\${easy.ems.aas.psk}")
    private lateinit var aasKey: String

    data class TeacherAutoAssessBody(@JsonProperty("solution") val solution: String)

    data class TeacherAutoAssessResponse(@JsonProperty("grade") val grade: Int,
                                         @JsonProperty("feedback") val feedback: String?)

    @Secured("ROLE_TEACHER")
    @PostMapping("/teacher/courses/{courseId}/exercises/{courseExId}/autoassess")
    fun teacherAutoAssess(@PathVariable("courseId") courseIdStr: String,
                          @PathVariable("courseExId") courseExIdStr: String,
                          @RequestBody body: TeacherAutoAssessBody,
                          caller: EasyUser): TeacherAutoAssessResponse {

        val callerId = caller.id

        log.debug { "Teacher $callerId autoassessing solution to exercise $courseExIdStr on course $courseIdStr" }

        val courseId = courseIdStr.toLong()
        val courseExId = courseExIdStr.toLong()

        if (!canTeacherAccessCourse(callerId, courseId)) {
            throw ForbiddenException("Teacher $callerId does not have access to course $courseId")
        }

        val aasId = getAasId(courseId, courseExId)
                ?: throw InvalidRequestException("Autoassessment not found for exercise $courseExId on course $courseId")

        val autoResult = autoAssess(aasId, body.solution, aasKey)
        return mapToResponse(autoResult)
    }

    private fun mapToResponse(autoResult: AutoAssessResponse): TeacherAutoAssessResponse =
            TeacherAutoAssessResponse(autoResult.grade, autoResult.feedback)
}


private fun getAasId(courseId: Long, courseExerciseId: Long): String? {
    return transaction {
        (CourseExercise innerJoin Exercise innerJoin ExerciseVer)
                .slice(ExerciseVer.aasId)
                .select {
                    CourseExercise.course eq courseId and
                            (CourseExercise.id eq courseExerciseId) and
                            ExerciseVer.validTo.isNull()
                }
                .map { it[ExerciseVer.aasId] }
                .firstOrNull()
    }
}
