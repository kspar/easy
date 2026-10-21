package core.ems.service.code

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.ems.service.code.similarity.SimilarityService
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.Valid
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * What a similarity check with this body would do, without doing it (EZ-1667): how many submissions
 * and pairs, which provider, and how long - priced from the submissions' sizes, never their text.
 *
 * The page asks as soon as an exercise and group are chosen, so a teacher sees "about 2 minutes" or
 * "too large for one check" before clicking Find, instead of a pair count that says nothing of how long
 * a pair takes. The check prices its cohort the same way and refuses one over the budget; resolving
 * both through [SimilarityRequests] is what keeps the answer here the answer there.
 */
@RestController
@RequestMapping("/v2")
class TeacherEstimateSimilarityController(
    private val similarityService: SimilarityService,
    private val requests: SimilarityRequests,
) {
    private val log = KotlinLogging.logger {}

    data class Resp(
        /** Submissions in the cohort, as the check would load them. */
        @get:JsonProperty("submissions") val submissions: Int,
        /** Pairs it would score: identical texts once, none between one student's own submissions. */
        @get:JsonProperty("pairs") val pairs: Long,
        @get:JsonProperty("provider") val provider: String,
        /** The language it would compare as, after any fallback its provider makes here. */
        @get:JsonProperty("language") val language: String,
        @get:JsonProperty("mode") val mode: String,
        /** As on the check: why it would not run as the course asked, or null. */
        @get:JsonProperty("provider_fallback") val providerFallback: String?,
        /** About how long, in whole seconds rounded up, on this server. */
        @get:JsonProperty("estimated_sec") val estimatedSec: Long,
        /** How long one check may run. */
        @get:JsonProperty("budget_sec") val budgetSec: Long,
        /** False when the check would be refused as SIMILARITY_TOO_COSTLY. */
        @get:JsonProperty("within_budget") val withinBudget: Boolean,
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/exercises/{exerciseId}/similarity/estimate")
    fun controller(
        @PathVariable("exerciseId") exIdString: String,
        @Valid @RequestBody body: TeacherCheckSimilarityController.Req,
        caller: EasyUser,
    ): Resp {
        // Counts, not the body, for the reason the check gives: request text in a log line can forge one.
        log.info {
            "Estimate similarity for exercise '$exIdString' by ${caller.id} over " +
                    "${body.courses.size} course(s), ${body.submissions?.size ?: 0} explicit submission(s)"
        }
        val resolved = requests.resolve(exIdString, body.courses.map { it.id }, body.submissions?.map { it.id }, caller)
        val estimate = requests.estimate(resolved)
        val provider = checkNotNull(similarityService.registry.provider(resolved.binding.provider))
        return Resp(
            estimate.submissions,
            estimate.pairs,
            provider.id,
            provider.comparesAs(resolved.binding.language).wireName,
            resolved.mode.name.lowercase(),
            resolved.binding.fallback,
            estimate.seconds,
            estimate.budgetSec,
            estimate.withinBudget,
        )
    }
}
