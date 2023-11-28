package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.GradeResp
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.assertCourseExerciseIsOnCourse
import core.ems.service.access_control.studentOnCourse
import core.ems.service.idToLongOrInvalidReq
import core.ems.service.toGradeRespOrNull
import core.util.DateTimeSerializer
import mu.KotlinLogging
import org.jetbrains.exposed.sql.*
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
            @JsonProperty("grade") val grade: GradeResp?)


    data class Resp(
        @JsonProperty("submissions") val submissions: List<SubmissionResp>,
        @JsonProperty("count") val submissionCount: Long
    )

    @GetMapping("/student/courses/{courseId}/exercises/{courseExerciseId}/submissions/all")
    fun controller(
        @PathVariable("courseId") courseIdStr: String,
        @PathVariable("courseExerciseId") courseExerciseIdStr: String,
        @RequestParam("limit", required = false) limitStr: String?,
        @RequestParam("offset", required = false) offsetStr: String?,
        caller: EasyUser
    ): Resp {

        log.debug { "Getting submissions for student ${caller.id} on course exercise $courseExerciseIdStr on course $courseIdStr (limit: $limitStr, offset: $offsetStr)" }
        val courseId = courseIdStr.idToLongOrInvalidReq()
        val courseExId = courseExerciseIdStr.idToLongOrInvalidReq()

        caller.assertAccess { studentOnCourse(courseId) }
        assertCourseExerciseIsOnCourse(courseExId, courseId)

        return selectStudentSubmissions(
            courseId,
            courseExId,
            caller.id,
            limitStr?.toIntOrNull(),
            offsetStr?.toLongOrNull()
        )
    }

    private fun selectStudentSubmissions(
        courseId: Long,
        courseExId: Long,
        studentId: String,
        limit: Int?,
        offset: Long?
    ): Resp = transaction {

        val submissionsQuery = (CourseExercise innerJoin Submission)
            .slice(
                Submission.id,
                Submission.solution,
                Submission.createdAt,
                Submission.autoGradeStatus,
                Submission.isAutoGrade,
                Submission.grade,
                Submission.seen
            )
            .select {
                CourseExercise.course eq courseId and
                        (CourseExercise.id eq courseExId) and
                        (Submission.student eq studentId)
            }
            .orderBy(
                Submission.createdAt to SortOrder.DESC
            )

        val count = submissionsQuery.count()

        val submissions = submissionsQuery
            .limit(limit ?: count.toInt(), offset ?: 0)
            .mapIndexed { i, it ->
                SubmissionResp(
                    it[Submission.id].value.toString(),
                    count.toInt() - i,
                    it[Submission.solution],
                    it[Submission.createdAt],
                    it[Submission.seen],
                    it[Submission.autoGradeStatus],
                    toGradeRespOrNull(it[Submission.grade], it[Submission.isAutoGrade])
                )
            }

        Resp(submissions, count)
    }
}

