package core.ems.service.code

import com.fasterxml.jackson.annotation.JsonProperty
import com.marcinmoskala.math.combinations
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.assertTeacherHasAccessToCourse
import core.ems.service.assertTeacherOrAdminHasAccessToExercise
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import core.exception.ReqError
import me.xdrop.fuzzywuzzy.FuzzySearch
import mu.KotlinLogging
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import java.util.*
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Size
import kotlin.math.roundToInt


private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class TeacherCheckSimilarityController {

    data class Req(@JsonProperty("courses", required = false) val courses: List<ReqCourse>,
                   @JsonProperty("submissions", required = false) val submissions: List<ReqSubmission>?)

    data class ReqCourse(@JsonProperty("id", required = true)
                         @field:NotBlank
                         @field:Size(max = 100)
                         val id: String)

    data class ReqSubmission(@JsonProperty("id", required = true)
                             @field:NotBlank
                             @field:Size(max = 100)
                             val id: String)


    data class Resp(@JsonProperty("submissions") val submissions: List<RespSubmission>,
                    @JsonProperty("scores") val scores: List<RespScore>)

    data class RespSubmission(
            @JsonProperty("id") val id: String,
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
    fun controller(@PathVariable("exerciseId") exIdString: String,
                   @Valid @RequestBody body: Req,
                   caller: EasyUser): Resp {

        log.debug { "Calculate similarity for exercise '$exIdString' by ${caller.id} with body: $body" }
        val exerciseId = exIdString.idToLongOrInvalidReq()
        assertTeacherOrAdminHasAccessToExercise(caller, exerciseId)


        val courses = body.courses.map { it.id.idToLongOrInvalidReq() }
        courses.forEach { assertTeacherHasAccessToCourse(caller.id, it) }


        val submissions = body.submissions?.map { it.id.idToLongOrInvalidReq() } ?: emptyList()

        val submissionResp: List<RespSubmission> = selectSubmissions(exerciseId, courses, submissions)
        log.info { "Analyzing source code similarity for ${submissionResp.size} submissions." }

        val scoresResp: List<RespScore> = calculateScores(submissionResp)
        return Resp(submissionResp, scoresResp)
    }
}


private fun selectSubmissions(exerciseId: Long, courses: List<Long>, submissions: List<Long>): List<TeacherCheckSimilarityController.RespSubmission> {
    return transaction {

        val query = (Course innerJoin CourseExercise innerJoin (Submission innerJoin (Student innerJoin Account)))
                .slice(
                        Course.title,
                        Submission.id,
                        Submission.solution,
                        Account.givenName,
                        Account.familyName
                )
                .select {
                    CourseExercise.exercise eq exerciseId and
                            (CourseExercise.course inList courses)
                }

        if (submissions.isNotEmpty()) {
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
            TeacherCheckSimilarityController.RespSubmission(
                    it[Submission.id].value.toString(),
                    it[Submission.solution],
                    it[Account.givenName],
                    it[Account.familyName],
                    it[Course.title]
            )
        }
    }
}

private fun calculateScores(submissions: List<TeacherCheckSimilarityController.RespSubmission>): List<TeacherCheckSimilarityController.RespScore> {
    return submissions.toSet()
            .combinations(2)
            .mapNotNull {
                it.zipWithNext().firstOrNull()
            }
            .map {
                TeacherCheckSimilarityController.RespScore(
                        it.first.id,
                        it.second.id,
                        diceCoefficient(
                                it.first.solution,
                                it.second.solution
                        ),
                        fuzzy(
                                it.first.solution,
                                it.second.solution
                        )
                )
            }
}

private fun fuzzy(s1: String, s2: String): Int {
    return FuzzySearch.ratio(s1, s2)
}

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

private fun splitIntoBigrams(s: String): TreeSet<String> {
    return when {
        s.length < 2 -> TreeSet<String>(listOf(s))
        else -> TreeSet<String>(s.windowed(2))
    }
}
