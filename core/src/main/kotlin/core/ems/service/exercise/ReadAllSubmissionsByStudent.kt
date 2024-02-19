package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.CourseExercise
import core.db.Submission
import core.ems.service.GradeResp
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.idToLongOrInvalidReq
import core.ems.service.toGradeRespOrNull
import core.util.DateTimeSerializer
import mu.KotlinLogging
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*


@RestController
@RequestMapping("/v2")
class ReadAllSubmissionsByStudent {
    private val log = KotlinLogging.logger {}

    data class SubmissionResp(
        @JsonProperty("id") val submissionId: String,
        @JsonSerialize(using = DateTimeSerializer::class) @JsonProperty("created_at") val createdAt: DateTime,
        @JsonProperty("grade") val grade: GradeResp?
    )

    data class Resp(@JsonProperty("submissions") val submissions: List<SubmissionResp>)

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

        caller.assertAccess { teacherOnCourse(courseId, true) }

        return selectTeacherAllSubmissions(courseId, courseExId, studentId)
    }

    private fun selectTeacherAllSubmissions(courseId: Long, courseExId: Long, studentId: String): Resp = transaction {
        Resp(
            (CourseExercise innerJoin Submission)
                .slice(
                    Submission.id,
                    Submission.createdAt,
                    Submission.grade,
                    Submission.isAutoGrade,
                    Submission.isGradedDirectly
                ).select {
                    CourseExercise.course eq courseId and (CourseExercise.id eq courseExId) and (Submission.student eq studentId)
                }.orderBy(Submission.createdAt, SortOrder.DESC).map {
                    SubmissionResp(
                        it[Submission.id].value.toString(),
                        it[Submission.createdAt],
                        toGradeRespOrNull(it[Submission.grade], it[Submission.isAutoGrade], it[Submission.isGradedDirectly])
                    )
                })
    }
}
