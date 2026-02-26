package core.ems.service

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonRawValue
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
import tools.jackson.module.kotlin.jacksonObjectMapper

data class InlineComment(
    val line_start: Int,
    val line_end: Int,
    val code: String,
    val text_md: String,
    val text_html: String,
    val type: String,
    val suggested_code: String? = null,
)

data class FeedbackData(
    val general_md: String?,
    val general_html: String?,
    val inline: List<InlineComment>,
)

private val objectMapper = jacksonObjectMapper()

fun buildFeedbackJson(
    generalMd: String?,
    inlineComments: List<InlineComment>?,
    markdownService: MarkdownService,
): String {
    val generalHtml = generalMd?.let { markdownService.mdToHtml(it) }
    val renderedInline = inlineComments?.map {
        it.copy(text_html = markdownService.mdToHtml(it.text_md))
    } ?: emptyList()
    val data = FeedbackData(generalMd, generalHtml, renderedInline)
    return objectMapper.writeValueAsString(data)
}

data class TeacherActivityResp(
    @get:JsonProperty("id") val id: String,
    @get:JsonProperty("submission_id") val submissionId: String,
    @get:JsonProperty("submission_number") val submissionNumber: Int,
    @get:JsonProperty("created_at") @get:JsonSerialize(using = DateTimeSerializer::class) val createdAt: DateTime,
    @get:JsonProperty("grade") val grade: Int?,
    @get:JsonProperty("edited_at") @get:JsonSerialize(using = DateTimeSerializer::class) val editedAt: DateTime?,
    @get:JsonProperty("feedback") @JsonRawValue val feedback: String?,
    @get:JsonProperty("teacher") val teacher: TeacherResp
)


data class ActivityResp(
    @get:JsonProperty("teacher_activities") val teacherActivities: List<TeacherActivityResp>,
)

fun selectStudentAllExerciseActivities(courseExId: Long, studentId: String): ActivityResp = transaction {
    val teacherActivities = (Submission innerJoin TeacherActivity)
        .select(
            TeacherActivity.id,
            TeacherActivity.submission,
            TeacherActivity.feedback,
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
                it[TeacherActivity.feedback],
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