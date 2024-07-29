package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.assertAssessmentControllerChecks
import core.ems.service.idToLongOrInvalidReq
import core.ems.service.singleOrInvalidRequest
import core.util.Zip
import core.util.writeZipFile
import mu.KotlinLogging
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.servlet.http.HttpServletResponse
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.NotEmpty


@RestController
@RequestMapping("/v2")
class TeacherDownloadCourseExerciseSubmissionsController {
    private val log = KotlinLogging.logger {}

    data class Req(@JsonProperty("submissions") @field:NotEmpty val submissions: Set<SubmissionReq>)

    data class SubmissionReq(@JsonProperty("id") @field:NotBlank val id: String)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/export/courses/{courseId}/exercises/{courseExerciseId}/submissions")
    fun controller(
        @PathVariable("courseId") courseIdString: String,
        @PathVariable("courseExerciseId") courseExerciseIdString: String,
        @Valid @RequestBody req: Req,
        caller: EasyUser,
        response: HttpServletResponse
    ) {

        log.info { "${caller.id} is downloading submissions ${req.submissions} on course $courseIdString for exercise $courseExerciseIdString" }

        val courseExId = courseExerciseIdString.idToLongOrInvalidReq()

        val submissions: List<Zip> = selectSubmissions(courseExId, req.submissions.map {
            val (_, _, submissionId) = assertAssessmentControllerChecks(
                caller,
                it.id,
                courseExerciseIdString,
                courseIdString,
            )
            submissionId
        })

        when (submissions.size) {
            1 -> {
                val submission = submissions.singleOrInvalidRequest()
                response.contentType = "application/octet-stream"
                response.setHeader("Content-disposition", "attachment; filename=${submission.name}")
                response.outputStream.use {
                    it.write(submission.content.toByteArray(Charsets.UTF_8))
                }
            }

            else -> {
                response.contentType = "application/zip"
                response.setHeader(
                    "Content-disposition",
                    "attachment; filename=submissions_${courseIdString}_$courseExerciseIdString.zip"
                )
                writeZipFile(submissions, response.outputStream)
            }
        }
    }


    private fun selectSubmissions(courseExId: Long, submissionIds: List<Long>): List<Zip> = transaction {
        val solutionFileName = selectSolutionFileName(courseExId)
        (Submission innerJoin Account)
            .select(Submission.id, Submission.solution, Submission.student, Account.givenName, Account.familyName)
            .where { Submission.id inList submissionIds }
            .orderBy(Submission.createdAt, SortOrder.DESC)
            .map {
                Zip(
                    it[Submission.solution],
                    createFileName(
                        it[Account.givenName],
                        it[Account.familyName],
                        it[Submission.id].value,
                        solutionFileName
                    )
                )
            }
    }

    private fun createFileName(givenName: String, familyName: String, submissionId: Long, solutionFileName: String): String =
        "${givenName}_${familyName}_${submissionId}_${solutionFileName}".replace(" ", "_")

    private fun selectSolutionFileName(courseExId: Long): String = transaction {
        (CourseExercise innerJoin Exercise innerJoin ExerciseVer)
            .select(ExerciseVer.solutionFileName)
            .where {
                (CourseExercise.id eq courseExId) and ExerciseVer.validTo.isNull()
            }
            .map { it[ExerciseVer.solutionFileName] }
            .singleOrInvalidRequest()
    }
}


