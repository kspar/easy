package core.ems.service.ai

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.AiProviderType
import core.db.Course
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import core.exception.ReqError
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * EZ-1711. Set, change or clear a course's AI provider. Teachers on the course and admins.
 *
 * The key is optional on every write after the first: the read endpoint never returns it, so a
 * teacher editing the model has nothing to paste back, and an absent key means "keep what is
 * there". `ai_props: null` clears all four columns, which is how AI features are switched off.
 */
@RestController
@RequestMapping("/v2")
class UpdateCourseAiPropsController {
    private val log = KotlinLogging.logger {}

    data class Req(@param:JsonProperty("ai_props") @field:Valid val aiProps: AiPropsReq?)

    data class AiPropsReq(
        @param:JsonProperty("provider") val provider: AiProviderType,
        @param:JsonProperty("model") @field:NotBlank @field:Size(max = 100) val model: String,
        @param:JsonProperty("base_url") @field:Size(max = 500) val baseUrl: String?,
        @param:JsonProperty("api_key") @field:Size(max = 500) val apiKey: String?,
        // EZ-1712. Tokens, in + out. Null lifts the limit; the counter is untouched either way —
        // resetting it is its own action (ResetCourseAiUsage), so that raising a budget is not
        // also, silently, a reset.
        @param:JsonProperty("token_budget") @field:Min(1) val tokenBudget: Long?,
        // Characters. Absent keeps the stored value; there is no "unlimited", the column is not null.
        @param:JsonProperty("max_solution_chars") @field:Min(1) @field:Max(1_000_000) val maxSolutionChars: Int?,
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PutMapping("/courses/{courseId}/ai")
    fun controller(
        @PathVariable("courseId") courseIdStr: String,
        @Valid @RequestBody body: Req,
        caller: EasyUser,
    ) {
        val courseId = courseIdStr.idToLongOrInvalidReq()
        caller.assertAccess { teacherOnCourse(courseId) }

        val props = body.aiProps
        if (props == null) {
            log.info { "Clearing AI props for course $courseId by ${caller.id}" }
            transaction {
                Course.update({ Course.id eq courseId }) {
                    it[aiProvider] = null
                    it[aiApiKey] = null
                    it[aiBaseUrl] = null
                    it[aiModel] = null
                    // The budget goes with the config; the counter and its reset date stay, so that
                    // switching AI off and on again does not also forget what it has cost so far.
                    it[aiTokenBudget] = null
                }
            }
            return
        }

        val newKey = props.apiKey?.takeIf { it.isNotBlank() }
        val newBaseUrl = props.baseUrl?.trim()?.takeIf { it.isNotBlank() }
        // Logged as a fact about the request, never as a value.
        log.info {
            "Updating AI props for course $courseId by ${caller.id} " +
                    "(provider: ${props.provider}, model: ${props.model}, key changed: ${newKey != null}, " +
                    "base url set: ${newBaseUrl != null})"
        }

        // The base URL is where core will POST a key and a student's code, from inside the
        // network. That is not a per-course decision a teacher gets to make: an admin sets it (a
        // proxy, a self-hosted endpoint), a teacher's save leaves whatever is there alone. And it
        // has to be a web address — RestTemplate's reaction to anything else is an exception on
        // the student's request, not a validation error on this one.
        if (newBaseUrl != null) {
            if (!caller.isAdmin()) {
                throw InvalidRequestException(
                    "Only an admin can set the AI base URL", ReqError.INVALID_PARAMETER_VALUE,
                    "field" to "base_url", notify = false
                )
            }
            if (!isWebUrl(newBaseUrl)) {
                throw InvalidRequestException(
                    "AI base URL must be an http(s) address", ReqError.INVALID_PARAMETER_VALUE,
                    "field" to "base_url", notify = false
                )
            }
        }

        transaction {
            val existing = Course.select(Course.aiApiKey, Course.aiBaseUrl)
                .where { Course.id eq courseId }
                .single()
            val existingKey = existing[Course.aiApiKey]?.takeIf { it.isNotBlank() }

            if (newKey == null && existingKey == null) {
                throw InvalidRequestException(
                    "An API key is required when none is configured yet",
                    ReqError.INVALID_PARAMETER_VALUE, "field" to "api_key", notify = false
                )
            }

            Course.update({ Course.id eq courseId }) {
                it[aiProvider] = props.provider
                it[aiModel] = props.model.trim()
                it[aiTokenBudget] = props.tokenBudget
                if (props.maxSolutionChars != null) it[aiMaxSolutionChars] = props.maxSolutionChars
                // An admin's write is authoritative for the URL, blank included; a teacher's keeps it.
                if (caller.isAdmin()) it[aiBaseUrl] = newBaseUrl
                if (newKey != null) it[aiApiKey] = newKey.trim()
            }
        }
    }

    private fun isWebUrl(s: String): Boolean = try {
        val uri = java.net.URI(s)
        (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank() && !s.contains('{')
    } catch (_: Exception) {
        false
    }
}
