package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.StatsSubmission
import core.db.Submission
import core.db.TeacherActivity
import core.ems.service.*
import core.ems.service.moodle.MoodleGradesSyncService
import core.util.SendMailService
import mu.KotlinLogging
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.joda.time.DateTime
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import javax.validation.constraints.Max
import javax.validation.constraints.Min
import javax.validation.constraints.NotNull


@RestController
@RequestMapping("/v2")
class TeacherGradeController(val moodleGradesSyncService: MoodleGradesSyncService, val mailService: SendMailService) {
    private val log = KotlinLogging.logger {}

    @Value("\${easy.core.activity.merge-window.s}")
    private lateinit var mergeWindowInSeconds: String

    data class Req(
        @JsonProperty("grade", required = true) @field:Min(0) @field:Max(100) val grade: Int,
        @JsonProperty("notify_student", required = true) @field:NotNull val notifyStudent: Boolean
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/teacher/courses/{courseId}/exercises/{courseExerciseId}/submissions/{submissionId}/grade")
    fun controller(
        @PathVariable("courseId") courseIdString: String,
        @PathVariable("courseExerciseId") courseExerciseIdString: String,
        @PathVariable("submissionId") submissionIdString: String,
        @Valid @RequestBody req: Req,
        caller: EasyUser
    ) {

        log.info { "Set grade by teacher ${caller.id} to submission $submissionIdString on course exercise $courseExerciseIdString on course $courseIdString" }

        val courseId = courseIdString.idToLongOrInvalidReq()
        val (callerId, courseExId, submissionId) = assertAssessmentControllerChecks(
            caller,
            submissionIdString,
            courseExerciseIdString,
            courseId,
        )

        insertOrUpdateGrade(callerId, submissionId, req, courseExId)
        moodleGradesSyncService.syncSingleGradeToMoodle(submissionId)

        if (req.notifyStudent) {
            val titles = getCourseAndExerciseTitles(courseId, courseExId)
            val email = selectStudentEmailBySubmissionId(submissionId)
            mailService.sendStudentChangedGrade(courseId, courseExId, titles.exerciseTitle, titles.courseTitle, email)
        }
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
            TeacherActivity.select(TeacherActivity.id, TeacherActivity.mergeWindowStart)
                .where { TeacherActivity.submission eq submissionId and (TeacherActivity.teacher eq teacherId) }
                .orderBy(TeacherActivity.mergeWindowStart, SortOrder.DESC)
                .firstNotNullOfOrNull {
                    if (!it[TeacherActivity.mergeWindowStart].hasSecondsPassed(mergeWindow)) it[TeacherActivity.id].value else null
                }
        }

}
