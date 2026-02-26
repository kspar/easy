package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.AutoGradeStatus
import core.db.CourseExercise
import core.db.StudentExerciseStatus
import core.db.Submission
import core.ems.service.*
import core.ems.service.access_control.RequireStudentVisible
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.assertCourseExerciseIsOnCourse
import core.ems.service.access_control.studentOnCourse
import core.util.DateTimeSerializer
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.joda.time.DateTime
import org.springframework.web.bind.annotation.*


@RestController
@RequestMapping("/v2")
class StudentReadSubmissionsController {
    private val log = KotlinLogging.logger {}

    data class SubmissionResp(
        @get:JsonProperty("id") val submissionId: String,
        @get:JsonProperty("number") val number: Int,
        @get:JsonProperty("solution") val solution: String,
        @get:JsonSerialize(using = DateTimeSerializer::class)
        @get:JsonProperty("submission_time") val submissionTime: DateTime,
        @get:JsonProperty("autograde_status") val autoGradeStatus: AutoGradeStatus,
        @get:JsonProperty("grade") val grade: GradeResp?,
        @get:JsonProperty("submission_status") val status: StudentExerciseStatus,
        @get:JsonProperty("auto_assessment") val autoAssessments: AutomaticAssessmentResp?
    )

    data class Resp(@get:JsonProperty("submissions") val submissions: List<SubmissionResp>)

    @GetMapping("/student/courses/{courseId}/exercises/{courseExerciseId}/submissions/all")
    fun controller(
        @PathVariable("courseId") courseIdStr: String,
        @PathVariable("courseExerciseId") courseExerciseIdStr: String,
        @RequestParam("limit", required = false) limitStr: String?,
        @RequestParam("offset", required = false) offsetStr: String?,
        caller: EasyUser
    ): Resp {

        log.info { "Getting submissions for student ${caller.id} on course exercise $courseExerciseIdStr on course $courseIdStr (limit: $limitStr, offset: $offsetStr)" }
        val courseId = courseIdStr.idToLongOrInvalidReq()
        val courseExId = courseExerciseIdStr.idToLongOrInvalidReq()

        caller.assertAccess { studentOnCourse(courseId) }
        assertCourseExerciseIsOnCourse(courseExId, courseId, RequireStudentVisible(caller.id))

        return Resp(
            selectStudentSubmissions(
                courseId, courseExId, caller.id,
                limitStr?.toIntOrNull(), offsetStr?.toLongOrNull()
            )
        )
    }

    private fun selectStudentSubmissions(
        courseId: Long,
        courseExId: Long,
        studentId: String,
        limit: Int?,
        offset: Long?
    ): List<SubmissionResp> = transaction {
        (CourseExercise innerJoin Submission)
            .select(
                CourseExercise.gradeThreshold,
                Submission.id,
                Submission.number,
                Submission.solution,
                Submission.createdAt,
                Submission.autoGradeStatus,
                Submission.isAutoGrade,
                Submission.grade,
                Submission.isGradedDirectly
            )
            .where {
                CourseExercise.course eq courseId and
                        (CourseExercise.id eq courseExId) and
                        (Submission.student eq studentId)
            }
            .orderBy(Submission.createdAt to SortOrder.DESC)
            .limit(limit ?: Int.MAX_VALUE)
            .offset(offset ?: 0)
            .mapIndexed { _, it ->
                val submissionId = it[Submission.id].value

                SubmissionResp(
                    submissionId.toString(),
                    it[Submission.number],
                    it[Submission.solution],
                    it[Submission.createdAt],
                    it[Submission.autoGradeStatus],
                    toGradeRespOrNull(
                        it[Submission.grade], it[Submission.isAutoGrade], it[Submission.isGradedDirectly]
                    ),
                    getStudentExerciseStatus(true, it[Submission.grade], it[CourseExercise.gradeThreshold]),
                    getLatestAutomaticAssessmentRespOrNull(submissionId)
                )
            }
    }
}

