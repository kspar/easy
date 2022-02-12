package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import core.aas.AutoAssessStatusObserver
import core.aas.ObserverCallerType
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.assertCourseExerciseIsOnCourse
import core.ems.service.assertStudentHasAccessToCourse
import core.ems.service.idToLongOrInvalidReq
import core.util.DateTimeSerializer
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.http.HttpStatus
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import javax.servlet.http.HttpServletResponse

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class StudentAwaitLatestSubmissionController(private val autoAssessStatusObserver: AutoAssessStatusObserver) {


    data class Resp(
        @JsonProperty("id") val submissionId: String,
        @JsonProperty("solution") val solution: String,
        @JsonSerialize(using = DateTimeSerializer::class)
        @JsonProperty("submission_time") val submissionTime: DateTime,
        @JsonProperty("autograde_status") val autoGradeStatus: AutoGradeStatus,
        @JsonProperty("grade_auto") val gradeAuto: Int?,
        @JsonProperty("feedback_auto") val feedbackAuto: String?,
        @JsonProperty("grade_teacher") val gradeTeacher: Int?,
        @JsonProperty("feedback_teacher") val feedbackTeacher: String?
    )

    @Secured("ROLE_STUDENT")
    @GetMapping("/student/courses/{courseId}/exercises/{courseExerciseId}/submissions/latest/await")
    fun controller(
        @PathVariable("courseId") courseIdStr: String,
        @PathVariable("courseExerciseId") courseExerciseIdStr: String,
        response: HttpServletResponse,
        caller: EasyUser
    ): Resp? {

        log.debug { "Getting latest submission for student ${caller.id} on course exercise $courseExerciseIdStr on course $courseIdStr" }
        val courseId = courseIdStr.idToLongOrInvalidReq()
        val courseExId = courseExerciseIdStr.idToLongOrInvalidReq()

        assertStudentHasAccessToCourse(caller.id, courseId)
        assertCourseExerciseIsOnCourse(courseExId, courseId)

        val submission = runBlocking {
            selectLatestStudentSubmission(
                courseId,
                courseExId,
                caller.id,
                autoAssessStatusObserver
            )
        }

        return if (submission != null) {
            submission
        } else {
            response.status = HttpStatus.NO_CONTENT.value()
            null
        }
    }
}

private suspend fun selectLatestStudentSubmission(
    courseId: Long,
    courseExId: Long,
    studentId: String,
    autoAssessStatusObserver: AutoAssessStatusObserver
): StudentAwaitLatestSubmissionController.Resp? {

    data class SubmissionPartial(val id: Long, val solution: String, val time: DateTime, val status: AutoGradeStatus)

    val latestSubmissionId = lastSubmissionId(courseId, courseExId, studentId) ?: return null

    // Wait while automatic grading in progress
    autoAssessStatusObserver.get(latestSubmissionId, ObserverCallerType.STUDENT)?.join()

    val lastSubmission = transaction {
        Submission
            .slice(Submission.createdAt, Submission.id, Submission.solution, Submission.autoGradeStatus)
            .select { Submission.id eq latestSubmissionId }
            .map {
                SubmissionPartial(
                    it[Submission.id].value,
                    it[Submission.solution],
                    it[Submission.createdAt],
                    it[Submission.autoGradeStatus]
                )
            }
            .singleOrNull()
    } ?: return null


    val autoAssessment = transaction { lastAutoAssessment(lastSubmission.id) }
    // Get teacher assessment after auto assessment because it might change during auto assessment
    val teacherAssessment = transaction { lastTeacherAssessment(lastSubmission.id) }


    return StudentAwaitLatestSubmissionController.Resp(
        lastSubmission.id.toString(),
        lastSubmission.solution,
        lastSubmission.time,
        lastSubmission.status,
        autoAssessment?.first,
        autoAssessment?.second,
        teacherAssessment?.first,
        teacherAssessment?.second
    )
}

private fun lastSubmissionId(courseId: Long, courseExId: Long, studentId: String): Long? {
    return transaction {
        (CourseExercise innerJoin Submission)
            .slice(Submission.id)
            .select {
                CourseExercise.course eq courseId and
                        (CourseExercise.id eq courseExId) and
                        (Submission.student eq studentId)
            }
            .orderBy(Submission.createdAt to SortOrder.DESC)
            .limit(1)
            .map { it[Submission.id].value }
            .firstOrNull()
    }
}

private fun lastAutoAssessment(submissionId: Long): Pair<Int, String?>? {
    return AutomaticAssessment.select { AutomaticAssessment.submission eq submissionId }
        .orderBy(AutomaticAssessment.createdAt to SortOrder.DESC)
        .limit(1)
        .map { it[AutomaticAssessment.grade] to it[AutomaticAssessment.feedback] }
        .firstOrNull()
}

private fun lastTeacherAssessment(submissionId: Long): Pair<Int, String?>? {
    return TeacherAssessment.select { TeacherAssessment.submission eq submissionId }
        .orderBy(TeacherAssessment.createdAt to SortOrder.DESC)
        .limit(1)
        .map { it[TeacherAssessment.grade] to it[TeacherAssessment.feedback] }
        .firstOrNull()
}