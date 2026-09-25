package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.CourseExercise
import core.db.StudentExerciseStatus
import core.db.Submission
import core.ems.service.GradeResp
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.idToLongOrInvalidReq
import core.ems.service.selectAttemptGrades
import core.ems.service.selectWork
import core.util.DateTimeSerializer
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.annotation.JsonSerialize


@RestController
@RequestMapping("/v2")
class ReadAllSubmissionsByStudent {
    private val log = KotlinLogging.logger {}

    data class SubmissionResp(
        @get:JsonProperty("id") val submissionId: String,
        @get:JsonProperty("submission_number") val submissionNumber: Int,
        @get:JsonSerialize(using = DateTimeSerializer::class) @get:JsonProperty("created_at") val createdAt: DateTime,
        @get:JsonProperty("status") val status: StudentExerciseStatus,
        @get:JsonProperty("grade") val grade: GradeResp?
    )

    data class Resp(@get:JsonProperty("submissions") val submissions: List<SubmissionResp>)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/teacher/courses/{courseId}/exercises/{courseExerciseId}/submissions/all/students/{studentId}")
    fun controller(
        @PathVariable("courseId") courseIdString: String,
        @PathVariable("courseExerciseId") courseExerciseIdString: String,
        @PathVariable("studentId") studentId: String,
        caller: EasyUser
    ): Resp {

        log.info { "Getting submissions for ${caller.id} by $studentId on course exercise $courseExerciseIdString on course $courseIdString" }
        val courseId = courseIdString.idToLongOrInvalidReq()
        val courseExId = courseExerciseIdString.idToLongOrInvalidReq()

        caller.assertAccess { teacherOnCourse(courseId) }

        return selectTeacherAllSubmissions(courseId, courseExId, studentId)
    }

    private fun selectTeacherAllSubmissions(courseId: Long, courseExId: Long, studentId: String): Resp = transaction {
        val rows = (CourseExercise innerJoin Submission)
            .select(
                CourseExercise.gradeThreshold,
                Submission.id,
                Submission.number,
                Submission.createdAt,
            ).where {
                CourseExercise.course eq courseId and (CourseExercise.id eq courseExId) and (Submission.student eq studentId)
            }
            .orderBy(Submission.createdAt to SortOrder.DESC, Submission.number to SortOrder.DESC, Submission.id to SortOrder.DESC)
            .toList()

        // EZ-1927: each attempt shows its own grade; the one on top shows the grade that counts.
        val grades = selectAttemptGrades(selectWork(courseExId, studentId), rows.map { it[Submission.id].value })

        Resp(rows.map {
            val submissionId = it[Submission.id].value
            val grade = grades[submissionId]
            SubmissionResp(
                submissionId.toString(),
                it[Submission.number],
                it[Submission.createdAt],
                getStudentExerciseStatus(true, grade?.grade, it[CourseExercise.gradeThreshold]),
                grade
            )
        })
    }
}
