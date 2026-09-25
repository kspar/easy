package core.ems.service.ai

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.AiFeedback
import core.db.AiProviderType
import core.db.Course
import core.db.CourseExercise
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.idToLongOrInvalidReq
import core.util.DateTimeSerializer
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.annotation.JsonSerialize

/**
 * EZ-1711. What a course has configured, minus the one thing that matters: the key never comes
 * back, only whether one is there and its last four characters, which is enough for a teacher to
 * tell their keys apart and useless to anyone else.
 *
 * Two layers. `ai_props` is the provider block and is null when AI is off. The rest is the
 * course's own — budget, counter, length limit, base URL — and is there whether AI is on or off,
 * because switching off keeps all of it and a teacher switching back on should see what they had.
 */
@RestController
@RequestMapping("/v2")
class ReadCourseAiPropsController {
    private val log = KotlinLogging.logger {}

    data class Resp(
        @get:JsonProperty("ai_props") val aiProps: AiPropsResp?,
        // EZ-1712. Budget in tokens, null = unlimited; the counter; since when it counts.
        @get:JsonProperty("token_budget") val tokenBudget: Long?,
        @get:JsonProperty("tokens_used") val tokensUsed: Long,
        @get:JsonProperty("tokens_reset_at") @get:JsonSerialize(using = DateTimeSerializer::class)
        val tokensResetAt: DateTime?,
        // The counter split by direction, summed from the audit rows since the reset. Output
        // tokens cost several times input, so a cost estimate needs the split, not the total.
        @get:JsonProperty("tokens_in_used") val tokensInUsed: Long,
        @get:JsonProperty("tokens_out_used") val tokensOutUsed: Long,
        // Longest solution, in characters, that gets an explanation.
        @get:JsonProperty("max_solution_chars") val maxSolutionChars: Int,
        // Admin-set; kept across a disable, so shown across one too.
        @get:JsonProperty("base_url") val baseUrl: String?,
    )

    data class AiPropsResp(
        @get:JsonProperty("provider") val provider: AiProviderType,
        @get:JsonProperty("model") val model: String,
        @get:JsonProperty("api_key_configured") val apiKeyConfigured: Boolean,
        @get:JsonProperty("api_key_hint") val apiKeyHint: String?,
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/courses/{courseId}/ai")
    fun controller(@PathVariable("courseId") courseIdStr: String, caller: EasyUser): Resp {
        log.info { "Getting AI props for course $courseIdStr by ${caller.id}" }
        val courseId = courseIdStr.idToLongOrInvalidReq()
        caller.assertAccess { teacherOnCourse(courseId) }
        return select(courseId)
    }

    private fun select(courseId: Long): Resp = transaction {
        val row = Course.select(
            Course.aiProvider, Course.aiApiKey, Course.aiBaseUrl, Course.aiModel,
            Course.aiTokenBudget, Course.aiTokensUsed, Course.aiTokensResetAt, Course.aiMaxSolutionChars,
        )
            .where { Course.id eq courseId }
            .single()

        val provider = row[Course.aiProvider]
        val key = row[Course.aiApiKey]?.takeIf { k -> k.isNotBlank() }
        val resetAt = row[Course.aiTokensResetAt]
        val (inUsed, outUsed) = sumTokensSince(courseId, resetAt)

        Resp(
            aiProps = provider?.let {
                AiPropsResp(
                    provider = it,
                    model = row[Course.aiModel]?.takeIf { m -> m.isNotBlank() } ?: AiProviderFactory.DEFAULT_MODEL,
                    apiKeyConfigured = key != null,
                    apiKeyHint = key?.takeLast(KEY_HINT_LENGTH),
                )
            },
            tokenBudget = row[Course.aiTokenBudget],
            tokensUsed = row[Course.aiTokensUsed],
            tokensResetAt = resetAt,
            tokensInUsed = inUsed,
            tokensOutUsed = outUsed,
            maxSolutionChars = row[Course.aiMaxSolutionChars],
            baseUrl = row[Course.aiBaseUrl]?.takeIf { u -> u.isNotBlank() },
        )
    }

    /**
     * In and out since the last reset (or ever), from the audit rows — the counter's own history.
     * FAILED rows count when they carry tokens, as the counter does.
     */
    private fun sumTokensSince(courseId: Long, since: DateTime?): Pair<Long, Long> {
        val inSum = AiFeedback.tokensIn.sum()
        val outSum = AiFeedback.tokensOut.sum()
        val row = (AiFeedback innerJoin CourseExercise)
            .select(inSum, outSum)
            .where {
                val onCourse = CourseExercise.course eq courseId
                if (since != null) onCourse and (AiFeedback.createdAt greater since) else onCourse
            }
            .single()
        return (row[inSum]?.toLong() ?: 0L) to (row[outSum]?.toLong() ?: 0L)
    }

    companion object {
        const val KEY_HINT_LENGTH = 4
    }
}
