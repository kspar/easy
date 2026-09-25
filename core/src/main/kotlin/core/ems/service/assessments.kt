package core.ems.service

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.cache.CachingService
import core.ems.service.cache.countSubmissionsInAutoAssessmentCache
import core.util.DateTimeSerializer
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.joda.time.DateTime
import tools.jackson.databind.annotation.JsonSerialize
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jacksonObjectMapper

data class TeacherActivityResp(
    @get:JsonProperty("id") val id: String,
    @get:JsonProperty("submission_id") val submissionId: String,
    @get:JsonProperty("submission_number") val submissionNumber: Int,
    @get:JsonProperty("created_at") @get:JsonSerialize(using = DateTimeSerializer::class) val createdAt: DateTime,
    @get:JsonProperty("grade") val grade: Int?,
    @get:JsonProperty("edited_at") @get:JsonSerialize(using = DateTimeSerializer::class) val editedAt: DateTime?,
    @get:JsonProperty("feedback_md") val feedbackMd: String?,
    @get:JsonProperty("feedback_html") val feedbackHtml: String?,
    @get:JsonProperty("teacher") val teacher: TeacherResp
)

data class InlineCommentResp(
    @get:JsonProperty("id") val id: String,
    @get:JsonProperty("submission_id") val submissionId: String,
    @get:JsonProperty("submission_number") val submissionNumber: Int,
    @get:JsonProperty("teacher") val teacher: TeacherResp,
    @get:JsonProperty("created_at") @get:JsonSerialize(using = DateTimeSerializer::class) val createdAt: DateTime,
    @get:JsonProperty("edited_at") @get:JsonSerialize(using = DateTimeSerializer::class) val editedAt: DateTime?,
    @get:JsonProperty("line_start") val lineStart: Int,
    @get:JsonProperty("line_end") val lineEnd: Int,
    @get:JsonProperty("code") val code: String,
    @get:JsonProperty("text_md") val textMd: String,
    @get:JsonProperty("text_html") val textHtml: String,
    // No `type`: it said 'comment' or 'suggestion' and meant exactly `suggested_code != null`.
    // Dropped in EZ-1777 along with the column. A client that still sends it on a write is
    // unaffected — FAIL_ON_UNKNOWN_PROPERTIES is off and the field carried nothing.
    @get:JsonProperty("suggested_code") val suggestedCode: String?,
)


/**
 * EZ-1712. What the feed shows for an AI explanation. No teacher, no grade, and none of the audit
 * columns (prompt, raw response, tokens) — those are for an operator with database access, not for
 * the student the prompt was about.
 */
data class AiFeedbackResp(
    @get:JsonProperty("id") val id: String,
    @get:JsonProperty("submission_id") val submissionId: String,
    @get:JsonProperty("submission_number") val submissionNumber: Int,
    @get:JsonProperty("created_at") @get:JsonSerialize(using = DateTimeSerializer::class) val createdAt: DateTime,
    @get:JsonProperty("provider") val provider: AiProviderType,
    @get:JsonProperty("model") val model: String,
    @get:JsonProperty("feedback_md") val feedbackMd: String,
    @get:JsonProperty("feedback_html") val feedbackHtml: String,
)

fun ResultRow.toAiFeedbackResp() = AiFeedbackResp(
    this[AiFeedback.id].value.toString(),
    this[AiFeedback.submission].value.toString(),
    this[Submission.number],
    this[AiFeedback.createdAt],
    this[AiFeedback.provider],
    this[AiFeedback.model],
    this[AiFeedback.feedbackMd].orEmpty(),
    this[AiFeedback.feedbackHtml].orEmpty(),
)

data class ActivityResp(
    @get:JsonProperty("teacher_activities") val teacherActivities: List<TeacherActivityResp>,
    // EZ-1712. A second list rather than a `type` on the first: the two have different authors,
    // different columns and different rules, and a client that has never heard of AI feedback keeps
    // working unchanged.
    @get:JsonProperty("ai_feedback") val aiFeedback: List<AiFeedbackResp>,
)

fun selectStudentAllExerciseActivities(courseExId: Long, studentId: String): ActivityResp = transaction {
    val teacherActivities = (Submission innerJoin TeacherActivity)
        .select(
            TeacherActivity.id,
            TeacherActivity.submission,
            TeacherActivity.feedbackMd,
            TeacherActivity.feedbackHtml,
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
            TeacherActivityResp(
                it[TeacherActivity.id].value.toString(),
                it[TeacherActivity.submission].value.toString(),
                it[Submission.number],
                it[TeacherActivity.mergeWindowStart],
                it[TeacherActivity.grade],
                it[TeacherActivity.editedAt],
                it[TeacherActivity.feedbackMd],
                it[TeacherActivity.feedbackHtml],
                selectTeacher(it[TeacherActivity.teacher].value)
            )
        }

    val aiFeedback = (Submission innerJoin AiFeedback)
        .select(
            AiFeedback.id, AiFeedback.submission, Submission.number, AiFeedback.createdAt,
            AiFeedback.provider, AiFeedback.model, AiFeedback.feedbackMd, AiFeedback.feedbackHtml,
        )
        .where {
            AiFeedback.student eq studentId and (AiFeedback.courseExercise eq courseExId) and
                    (AiFeedback.status eq AiFeedbackStatus.OK)
        }
        .orderBy(AiFeedback.createdAt, SortOrder.ASC)
        .map { it.toAiFeedbackResp() }

    ActivityResp(teacherActivities, aiFeedback)
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

        // EZ-1926. The grader asked for a teacher's eyes. Every attempt of this student, not just
        // this one, for the reason SetSubmissionFlagged gives: the flag is read off the latest
        // submission, and the next attempt would otherwise hide it. Only ever raised here — a
        // result without the field, or with false, leaves whatever a teacher set alone.
        if (feedbackFlagsForReview(newFeedback)) {
            Submission.update({ (Submission.courseExercise eq courseExId) and (Submission.student eq studentId) }) {
                it[flagged] = true
            }
        }

        cachingService.invalidate(countSubmissionsInAutoAssessmentCache)
    }
}

/**
 * Whether a grader's result asks for review: an OK_V3 document with a top-level `flag_for_review`
 * that is the JSON boolean `true` (EZ-1926, `doc/aae/feedback-format.md`).
 *
 * Everything else is false — legacy plain text, a document that does not parse, the field absent,
 * `false`, or a value that is not a boolean. A grader that writes `"true"` has not met the format,
 * and the answer to that is a validator failure on its side, not a flag on a student.
 */
internal fun feedbackFlagsForReview(feedback: String?): Boolean {
    if (feedback.isNullOrBlank()) return false
    val root = try {
        feedbackMapper.readTree(feedback)
    } catch (_: Exception) {
        return false
    }
    if (root !is ObjectNode || root.get("result_type")?.asString() != "OK_V3") return false
    val flag = root.get("flag_for_review") ?: return false
    return flag.isBoolean && flag.asBoolean()
}

private val feedbackMapper = jacksonObjectMapper()


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