package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.assertAssessmentControllerChecks
import core.ems.service.cache.CachingService
import core.ems.service.hasSecondsPassed
import core.ems.service.moodle.MoodleGradesSyncService
import core.ems.service.selectStudentBySubmissionId
import mu.KotlinLogging
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import javax.validation.constraints.Max
import javax.validation.constraints.Min


@RestController
@RequestMapping("/v2")
class TeacherGradeController(val moodleGradesSyncService: MoodleGradesSyncService) {
    private val log = KotlinLogging.logger {}

    @Value("\${easy.core.activity.merge-window.s}")
    private lateinit var mergeWindowInSeconds: String

    data class Req(@JsonProperty("grade", required = true) @field:Min(0) @field:Max(100) val grade: Int)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/teacher/courses/{courseId}/exercises/{courseExerciseId}/submissions/{submissionId}/grade")
    fun controller(
        @PathVariable("courseId") courseIdString: String,
        @PathVariable("courseExerciseId") courseExerciseIdString: String,
        @PathVariable("submissionId") submissionIdString: String,
        @Valid @RequestBody assessment: Req,
        caller: EasyUser
    ) {

        log.debug { "Set grade by teacher ${caller.id} to submission $submissionIdString on course exercise $courseExerciseIdString on course $courseIdString" }

        val (callerId, courseExId, submissionId) = assertAssessmentControllerChecks(
            caller,
            submissionIdString,
            courseExerciseIdString,
            courseIdString,
        )

        insertOrUpdateGrade(callerId, submissionId, assessment, courseExId)
        moodleGradesSyncService.syncSingleGradeToMoodle(submissionId)
    }

    private fun insertOrUpdateGrade(teacherId: String, submissionId: Long, assessment: Req, courseExId: Long) =
        transaction {
            val previousId = getIdIfShouldMerge(submissionId, teacherId, mergeWindowInSeconds.toInt())

            if (previousId != null) {
                TeacherAssessment.update({ TeacherAssessment.id eq previousId }) {
                    it[grade] = assessment.grade
                    it[mergeWindowStart] = DateTime.now()
                }
            } else {
                TeacherAssessment.insert {
                    it[student] = selectStudentBySubmissionId(submissionId)
                    it[courseExercise] = courseExId
                    it[submission] = submissionId
                    it[teacher] = teacherId
                    it[grade] = assessment.grade
                    it[mergeWindowStart] = DateTime.now()
                }
            }

            Submission.update({ Submission.id eq submissionId }) {
                it[grade] = grade
                it[isAutoGrade] = false
            }
        }

    private fun getIdIfShouldMerge(submissionId: Long, teacherId: String, mergeWindow: Int): Long? =
        transaction {
            TeacherAssessment.slice(TeacherAssessment.id, TeacherAssessment.mergeWindowStart)
                .select {
                    TeacherAssessment.submission eq submissionId and (TeacherAssessment.teacher eq teacherId)

                }.orderBy(TeacherAssessment.mergeWindowStart, SortOrder.DESC)
                .firstNotNullOfOrNull {
                    if (!it[TeacherAssessment.mergeWindowStart].hasSecondsPassed(mergeWindow)) it[TeacherAssessment.id].value else null
                }
        }

}
