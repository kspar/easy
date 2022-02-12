package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.assertCourseExerciseIsOnCourse
import core.ems.service.assertStudentHasAccessToCourse
import core.ems.service.idToLongOrInvalidReq
import core.util.DateTimeSerializer
import mu.KotlinLogging
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.web.bind.annotation.*

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class StudentReadSubmissionsController {

    data class SubmissionResp(
            @JsonProperty("id") val submissionId: String,
            @JsonProperty("solution") val solution: String,
            @JsonSerialize(using = DateTimeSerializer::class)
            @JsonProperty("submission_time") val submissionTime: DateTime,
            @JsonProperty("autograde_status") val autoGradeStatus: AutoGradeStatus,
            @JsonProperty("grade_auto") val gradeAuto: Int?,
            @JsonProperty("feedback_auto") val feedbackAuto: String?,
            @JsonProperty("grade_teacher") val gradeTeacher: Int?,
            @JsonProperty("feedback_teacher") val feedbackTeacher: String?)

    data class Resp(@JsonProperty("submissions") val submissions: List<SubmissionResp>,
                    @JsonProperty("count") val submissionCount: Long)

    @GetMapping("/student/courses/{courseId}/exercises/{courseExerciseId}/submissions/all")
    fun controller(@PathVariable("courseId") courseIdStr: String,
                   @PathVariable("courseExerciseId") courseExerciseIdStr: String,
                   @RequestParam("limit", required = false) limitStr: String?,
                   @RequestParam("offset", required = false) offsetStr: String?,
                   caller: EasyUser): Resp {

        log.debug { "Getting submissions for student ${caller.id} on course exercise $courseExerciseIdStr on course $courseIdStr (limit: $limitStr, offset: $offsetStr)" }
        val courseId = courseIdStr.idToLongOrInvalidReq()
        val courseExId = courseExerciseIdStr.idToLongOrInvalidReq()

        assertStudentHasAccessToCourse(caller.id, courseId)
        assertCourseExerciseIsOnCourse(courseExId, courseId)

        return selectStudentSubmissions(courseId, courseExId, caller.id, limitStr?.toIntOrNull(), offsetStr?.toLongOrNull())
    }
}


private fun selectStudentSubmissions(courseId: Long, courseExId: Long, studentId: String, limit: Int?, offset: Long?):
        StudentReadSubmissionsController.Resp {

    return transaction {

        val count = (CourseExercise innerJoin Submission)
                .select {
                    CourseExercise.course eq courseId and
                            (CourseExercise.id eq courseExId) and
                            (Submission.student eq studentId)
                }.count()

        val distinctSubmissionId = Submission.id.distinctOn().alias("submission_id")
        val autoGradeAlias = AutomaticAssessment.grade.alias("auto_grade")
        val autoFeedbackAlias = AutomaticAssessment.feedback.alias("auto_feedback")
        val subQuery = (CourseExercise innerJoin Submission leftJoin AutomaticAssessment leftJoin TeacherAssessment)
                .slice(distinctSubmissionId, Submission.solution, Submission.createdAt, Submission.autoGradeStatus,
                        TeacherAssessment.grade, TeacherAssessment.feedback, autoGradeAlias, autoFeedbackAlias)
                .select {
                    CourseExercise.course eq courseId and
                            (CourseExercise.id eq courseExId) and
                            (Submission.student eq studentId)
                }.orderBy(distinctSubmissionId to SortOrder.ASC,
                        AutomaticAssessment.createdAt to SortOrder.DESC,
                        TeacherAssessment.createdAt to SortOrder.DESC)

        val subTable = subQuery.alias("t")
        val wrapQuery = subTable
                .slice(subTable[distinctSubmissionId], subTable[Submission.solution], subTable[Submission.createdAt],
                        subTable[Submission.autoGradeStatus], subTable[TeacherAssessment.grade], subTable[TeacherAssessment.feedback],
                        subTable[autoGradeAlias], subTable[autoFeedbackAlias])
                .selectAll()

        val submissions = wrapQuery
                .orderBy(subTable[Submission.createdAt] to SortOrder.DESC)
                .limit(limit ?: count.toInt(), offset ?: 0)
                .map {
                    StudentReadSubmissionsController.SubmissionResp(
                            it[subTable[distinctSubmissionId]].value.toString(),
                            it[subTable[Submission.solution]],
                            it[subTable[Submission.createdAt]],
                            it[subTable[Submission.autoGradeStatus]],
                            it[subTable[autoGradeAlias]],
                            it[subTable[autoFeedbackAlias]],
                            it[subTable[TeacherAssessment.grade]],
                            it[subTable[TeacherAssessment.feedback]]
                    )
                }

        StudentReadSubmissionsController.Resp(submissions, count)
    }
}
