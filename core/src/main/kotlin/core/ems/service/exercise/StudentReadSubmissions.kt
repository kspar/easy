package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.AutoGradeStatus
import core.db.CourseExercise
import core.db.StudentExerciseStatus
import core.db.Submission
import core.ems.service.GradeResp
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.assertCourseExerciseIsOnCourse
import core.ems.service.access_control.studentOnCourse
import core.ems.service.idToLongOrInvalidReq
import core.ems.service.toGradeRespOrNull
import core.util.DateTimeSerializer
import mu.KotlinLogging
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.web.bind.annotation.*


@RestController
@RequestMapping("/v2")
class StudentReadSubmissionsController {
    private val log = KotlinLogging.logger {}

    data class SubmissionResp(
        @JsonProperty("id") val submissionId: String,
        @JsonProperty("number") val number: Int,
        @JsonProperty("solution") val solution: String,
        @JsonSerialize(using = DateTimeSerializer::class)
        @JsonProperty("submission_time") val submissionTime: DateTime,
        @JsonProperty("seen") val seen: Boolean,
        @JsonProperty("autograde_status") val autoGradeStatus: AutoGradeStatus,
        @JsonProperty("grade") val grade: GradeResp?,
        @JsonProperty("submission_status") val status: StudentExerciseStatus,
    )

    data class Resp(
        @JsonProperty("submissions") val submissions: List<SubmissionResp>,
    )

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
        assertCourseExerciseIsOnCourse(courseExId, courseId)

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
            .slice(
                CourseExercise.gradeThreshold,
                Submission.id,
                Submission.number,
                Submission.solution,
                Submission.createdAt,
                Submission.autoGradeStatus,
                Submission.isAutoGrade,
                Submission.grade,
                Submission.seen,
                Submission.isGradedDirectly
            )
            .select {
                CourseExercise.course eq courseId and
                        (CourseExercise.id eq courseExId) and
                        (Submission.student eq studentId)
            }
            .orderBy(
                Submission.createdAt to SortOrder.DESC
            )
            .limit(limit ?: Int.MAX_VALUE, offset ?: 0)
            .mapIndexed { i, it ->
                SubmissionResp(
                    it[Submission.id].value.toString(),
                    it[Submission.number],
                    it[Submission.solution],
                    it[Submission.createdAt],
                    it[Submission.seen],
                    it[Submission.autoGradeStatus],
                    toGradeRespOrNull(
                        it[Submission.grade], it[Submission.isAutoGrade], it[Submission.isGradedDirectly]
                    ),
                    getStudentExerciseStatus(true, it[Submission.grade], it[CourseExercise.gradeThreshold])
                )
            }
    }
}

