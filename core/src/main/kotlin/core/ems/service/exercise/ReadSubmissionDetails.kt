package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.Submission
import core.ems.service.*
import core.exception.InvalidRequestException
import core.exception.ReqError
import core.util.DateTimeSerializer
import mu.KotlinLogging
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v2")
class ReadSubmissionDetails {
    private val log = KotlinLogging.logger {}

    data class Resp(
        @JsonProperty("submission_number") val submissionNumber: Int,
        @JsonProperty("solution") val solution: String,
        @JsonProperty("seen") val seen: Boolean,
        @JsonSerialize(using = DateTimeSerializer::class)
        @JsonProperty("created_at") val createdAt: DateTime,
        @JsonProperty("grade") val grade: GradeResp?
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/teacher/courses/{courseId}/exercises/{courseExerciseId}/submissions/{submissionId}")
    fun controller(
        @PathVariable("courseId") courseIdString: String,
        @PathVariable("courseExerciseId") courseExerciseIdString: String,
        @PathVariable("submissionId") submissionIdString: String,
        caller: EasyUser
    ): Resp {

        log.info { "Getting submissions details for ${caller.id} on course exercise $courseExerciseIdString on course $courseIdString" }


        val (_, courseExId, submissionId) = assertAssessmentControllerChecks(
            caller,
            submissionIdString,
            courseExerciseIdString,
            courseIdString,
        )

        return selectTeacherAllSubmissions(submissionId, courseExId)
    }

    private fun selectTeacherAllSubmissions(submissionId: Long, courseExId: Long): Resp = transaction {
        val submissionIdNumbers = getSubmissionNumbers(selectStudentBySubmissionId(submissionId).value, courseExId)

        Submission.slice(
            Submission.id,
            Submission.grade,
            Submission.isAutoGrade,
            Submission.solution,
            Submission.createdAt,
            Submission.seen
        )
            .select { Submission.id eq submissionId and (Submission.courseExercise eq courseExId) }
            .map {
                Resp(
                    submissionIdNumbers[submissionId] ?: throw InvalidRequestException(
                        "Submission ID number $submissionId not mappable to order number (mapping to order not found)",
                        ReqError.ENTITY_WITH_ID_NOT_FOUND
                    ),
                    it[Submission.solution],
                    it[Submission.seen],
                    it[Submission.createdAt],
                    toGradeRespOrNull(it[Submission.grade], it[Submission.isAutoGrade])
                )

            }.singleOrInvalidRequest()
    }
}
