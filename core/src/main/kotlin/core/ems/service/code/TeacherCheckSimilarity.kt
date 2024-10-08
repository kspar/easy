package core.ems.service.code

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.Account
import core.db.Course
import core.db.CourseExercise
import core.db.Submission
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import core.exception.ReqError
import core.util.DateTimeSerializer
import core.util.forEachCombination
import me.xdrop.fuzzywuzzy.FuzzySearch
import mu.KotlinLogging
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import java.util.*
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Size
import kotlin.math.roundToInt


@RestController
@RequestMapping("/v2")
class TeacherCheckSimilarityController {
    private val log = KotlinLogging.logger {}

    /**
     * How to call this service:
     * 1) exerciseId only - compare over all courses and all submissions
     * 2) exerciseId, courseId(s) - compare over all given courses and all submissions
     * 3) exerciseId, courseId(s), submissionIds - compare over all given submissions (must be on given courses)
     */
    data class Req(
        @JsonProperty("courses", required = false) val courses: List<ReqCourse>,
        @JsonProperty("submissions", required = false) val submissions: List<ReqSubmission>?
    )

    data class ReqCourse(
        @JsonProperty("id", required = true)
        @field:NotBlank
        @field:Size(max = 100)
        val id: String
    )

    data class ReqSubmission(
        @JsonProperty("id", required = true)
        @field:NotBlank
        @field:Size(max = 100)
        val id: String
    )


    data class Resp(
        @JsonProperty("submissions") val submissions: List<RespSubmission>,
        @JsonProperty("scores") val scores: List<RespScore>
    )

    data class RespSubmission(
        @JsonProperty("id") val id: String,
        @JsonSerialize(using = DateTimeSerializer::class)
        @JsonProperty("created_at") val createdAt: DateTime,
        // TODO: number
        @JsonProperty("solution") val solution: String,
        @JsonProperty("given_name") val givenName: String,
        @JsonProperty("family_name") val familyName: String,
        @JsonProperty("course_title") val courseTitle: String
    )

    data class RespScore(
        @JsonProperty("sub_1") val sub1: String,
        @JsonProperty("sub_2") val sub2: String,
        @JsonProperty("score_a") val scoreA: Int,
        @JsonProperty("score_b") val scoreB: Int
    )


    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/exercises/{exerciseId}/similarity")
    fun controller(
        @PathVariable("exerciseId") exIdString: String,
        @Valid @RequestBody body: Req,
        caller: EasyUser
    ): Resp {

        log.info { "Calculate similarity for exercise '$exIdString' by ${caller.id} with body: $body" }
        val exerciseId = exIdString.idToLongOrInvalidReq()
        val courses = body.courses.map { it.id.idToLongOrInvalidReq() }

        caller.assertAccess {
            courses.forEach { teacherOnCourse(it) }
        }

        val submissions = body.submissions?.map { it.id.idToLongOrInvalidReq() }

        val submissionResp: List<RespSubmission> = selectSubmissions(exerciseId, courses, submissions)
        log.info { "Analyzing source code similarity for ${submissionResp.size} submissions." }

        // Return top 100 highest scores
        val scoresResp: List<RespScore> = calculateScores(submissionResp)
            .sortedByDescending { it.scoreA + it.scoreB }
            .take(100)

        return Resp(submissionResp, scoresResp)
    }

    private fun selectSubmissions(
        exerciseId: Long,
        courses: List<Long>,
        submissions: List<Long>?
    ): List<RespSubmission> = transaction {

        val query = (Course innerJoin CourseExercise innerJoin (Submission innerJoin Account))
            .select(
                Course.title,
                Submission.id,
                Submission.createdAt,
                Submission.solution,
                Account.givenName,
                Account.familyName
            )
            .where {
                CourseExercise.exercise eq exerciseId and
                        (CourseExercise.course inList courses)
            }

        if (submissions != null) {
            query.andWhere {
                Submission.id inList submissions
            }

            val count = query.count()
            if (count < submissions.size) {
                throw InvalidRequestException(
                    "Number of submissions '${submissions.size}' requested to analyze does not equal to the number of submissions found: '$count'.",
                    ReqError.ENTITY_WITH_ID_NOT_FOUND
                )
            }
        }


        query.map {
            RespSubmission(
                it[Submission.id].value.toString(),
                it[Submission.createdAt],
                it[Submission.solution],
                it[Account.givenName],
                it[Account.familyName],
                it[Course.title]
            )
        }
    }

    // TODO: 28.09.2022 add diff-match-batch Google's diff algorithm?
    // TODO: 28.09.2022 set a timeout
    private fun calculateScores(submissions: List<RespSubmission>): List<RespScore> {
        if (submissions.size < 2) {
            return emptyList()
        }
        val result = mutableSetOf<RespScore>()
        submissions.forEachCombination(2) {
            val first = it.toList()[0]
            val second = it.toList()[1]
            val r = RespScore(
                first.id,
                second.id,
                diceCoefficient(
                    first.solution,
                    second.solution
                ),
                fuzzy(
                    first.solution,
                    second.solution
                )
            )

            result.add(r)
        }
        return result.toList()
    }

    private fun fuzzy(s1: String, s2: String): Int = FuzzySearch.ratio(s1, s2)

    /**
     * Rewrote from: https://github.com/rrice/java-string-similarity
     * https://en.wikipedia.org/wiki/S%c3%b8rensen%e2%80%93Dice_coefficient
     */
    private fun diceCoefficient(first: String, second: String): Int {
        val s1 = splitIntoBigrams(first)
        val s2 = splitIntoBigrams(second)

        s1.retainAll(s2)

        val nt = s1.size
        return (100 * (2.0 * nt / (s1.size + s2.size))).roundToInt()
    }

    private fun splitIntoBigrams(s: String): TreeSet<String> = when {
        s.length < 2 -> TreeSet<String>(listOf(s))
        else -> TreeSet<String>(s.windowed(2))
    }
}
