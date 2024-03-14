package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.StatsSubmission
import core.db.Submission
import core.db.TeacherActivity
import core.ems.service.assertAssessmentControllerChecks
import core.ems.service.hasSecondsPassed
import core.ems.service.moodle.MoodleGradesSyncService
import core.ems.service.selectPseudonym
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

        log.info { "Set grade by teacher ${caller.id} to submission $submissionIdString on course exercise $courseExerciseIdString on course $courseIdString" }

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
            val time = DateTime.now()
            if (previousId != null) {
                TeacherActivity.update({ TeacherActivity.id eq previousId }) {
                    it[grade] = assessment.grade
                    it[mergeWindowStart] = time
                }
            } else {
                TeacherActivity.insert {
                    it[student] = selectStudentBySubmissionId(submissionId)
                    it[courseExercise] = courseExId
                    it[submission] = submissionId
                    it[teacher] = teacherId
                    it[grade] = assessment.grade
                    it[mergeWindowStart] = time
                }
            }

            Submission.update({ Submission.id eq submissionId }) {
                it[grade] = assessment.grade
                it[isAutoGrade] = false
                it[isGradedDirectly] = true
            }

            StatsSubmission.update({ StatsSubmission.submissionId eq submissionId }) {
                it[latestTeacherPseudonym] = selectPseudonym(teacherId)
                it[latestTeacherActivityUpdate] = time
                it[teacherPoints] = assessment.grade
            }
        }

    private fun getIdIfShouldMerge(submissionId: Long, teacherId: String, mergeWindow: Int): Long? =
        transaction {
            TeacherActivity.slice(TeacherActivity.id, TeacherActivity.mergeWindowStart)
                .select {
                    TeacherActivity.submission eq submissionId and (TeacherActivity.teacher eq teacherId)

                }.orderBy(TeacherActivity.mergeWindowStart, SortOrder.DESC)
                .firstNotNullOfOrNull {
                    if (!it[TeacherActivity.mergeWindowStart].hasSecondsPassed(mergeWindow)) it[TeacherActivity.id].value else null
                }
        }

}
