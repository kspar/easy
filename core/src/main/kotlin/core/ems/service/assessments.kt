package core.ems.service

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.cache.CachingService
import core.ems.service.cache.countSubmissionsInAutoAssessmentCache
import core.util.DateTimeSerializer
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime

data class FeedbackResp(
    @JsonProperty("feedback_html") val feedbackHtml: String,
    @JsonProperty("feedback_adoc") val feedbackAdoc: String
)

data class TeacherActivityResp(
    @JsonProperty("id") val id: String,
    @JsonProperty("submission_id") val submissionId: String,
    @JsonProperty("submission_number") val submissionNumber: Int,
    @JsonProperty("created_at") @JsonSerialize(using = DateTimeSerializer::class) val createdAt: DateTime,
    @JsonProperty("grade") val grade: Int?,
    @JsonProperty("edited_at") @JsonSerialize(using = DateTimeSerializer::class) val editedAt: DateTime?,
    @JsonProperty("feedback") val feedback: FeedbackResp?,
    @JsonProperty("teacher") val teacher: TeacherResp
)


data class ActivityResp(
    @JsonProperty("teacher_activities") val teacherActivities: List<TeacherActivityResp>,
)

fun selectStudentAllExerciseActivities(courseExId: Long, studentId: String): ActivityResp = transaction {
    val teacherActivities = (Submission innerJoin TeacherActivity)
        .select(
            TeacherActivity.id,
            TeacherActivity.submission,
            TeacherActivity.feedbackHtml,
            TeacherActivity.feedbackAdoc,
            TeacherActivity.mergeWindowStart,
            TeacherActivity.grade,
            TeacherActivity.editedAt,
            TeacherActivity.teacher,
            Submission.number
        ).where {
            TeacherActivity.student eq studentId and (TeacherActivity.courseExercise eq courseExId)
        }
        .orderBy(TeacherActivity.mergeWindowStart, SortOrder.ASC)
        .map {
            val html = it[TeacherActivity.feedbackHtml]
            val adoc = it[TeacherActivity.feedbackAdoc]

            TeacherActivityResp(
                it[TeacherActivity.id].value.toString(),
                it[TeacherActivity.submission].value.toString(),
                it[Submission.number],
                it[TeacherActivity.mergeWindowStart],
                it[TeacherActivity.grade],
                it[TeacherActivity.editedAt],
                if (html != null && adoc != null) FeedbackResp(html, adoc) else null,
                selectTeacher(it[TeacherActivity.teacher].value)
            )
        }

    ActivityResp(teacherActivities)
}


fun assertAssessmentControllerChecks(
    caller: EasyUser, submissionIdString: String, courseExerciseIdString: String, courseIdString: String,
): Triple<String, Long, Long> = assertAssessmentControllerChecks(
    caller,
    submissionIdString,
    courseExerciseIdString,
    courseIdString.idToLongOrInvalidReq()
)

fun assertAssessmentControllerChecks(
    caller: EasyUser, submissionIdString: String, courseExerciseIdString: String, courseId: Long,
): Triple<String, Long, Long> {

    val callerId = caller.id
    val courseExId = courseExerciseIdString.idToLongOrInvalidReq()
    val submissionId = submissionIdString.idToLongOrInvalidReq()

    caller.assertAccess { teacherOnCourse(courseId) }

    assertSubmissionExists(submissionId, courseExId, courseId)
    return Triple(callerId, courseExId, submissionId)
}


fun insertAutoAssFailed(submissionId: Long, cachingService: CachingService) = transaction {
    Submission.update({ Submission.id eq submissionId }) {
        it[autoGradeStatus] = AutoGradeStatus.FAILED
    }
    cachingService.invalidate(countSubmissionsInAutoAssessmentCache)
}

fun insertAutogradeActivity(
    newGrade: Int,
    newFeedback: String?,
    submissionId: Long,
    cachingService: CachingService,
    courseExId: Long,
    studentId: String
) {
    transaction {
        val time = DateTime.now()
        AutogradeActivity.insert {
            it[student] = studentId
            it[courseExercise] = courseExId
            it[submission] = submissionId
            it[createdAt] = time
            it[grade] = newGrade
            it[feedback] = newFeedback
        }

        Submission.update({ Submission.id eq submissionId }) {
            it[autoGradeStatus] = AutoGradeStatus.COMPLETED
            if (!anyPreviousTeacherActivityContainsGrade(studentId, courseExId)) {
                it[grade] = newGrade
                it[isAutoGrade] = true
                it[isGradedDirectly] = true
            }
        }

        StatsSubmission.update({ StatsSubmission.submissionId eq submissionId }) {
            it[autoPoints] = newGrade
            it[autoGradedAt] = time
        }

        cachingService.invalidate(countSubmissionsInAutoAssessmentCache)
    }
}


fun selectGraderType(courseExId: Long): GraderType = transaction {
    (CourseExercise innerJoin Exercise innerJoin ExerciseVer)
        .select(ExerciseVer.graderType)
        .where { CourseExercise.id eq courseExId and ExerciseVer.validTo.isNull() }
        .map { it[ExerciseVer.graderType] }
        .single()
}

fun selectAutoExId(courseExId: Long): Long? = transaction {
    (CourseExercise innerJoin Exercise innerJoin ExerciseVer)
        .select(ExerciseVer.autoExerciseId)
        .where { CourseExercise.id eq courseExId and ExerciseVer.validTo.isNull() }
        .map { it[ExerciseVer.autoExerciseId] }
        .single()?.value
}

private fun anyPreviousTeacherActivityContainsGrade(studentId: String, courseExercise: Long): Boolean =
    transaction {
        TeacherActivity
            .selectAll()
            .where { (TeacherActivity.student eq studentId) and (TeacherActivity.courseExercise eq courseExercise) and TeacherActivity.grade.isNotNull() }
            .count() > 0
    }