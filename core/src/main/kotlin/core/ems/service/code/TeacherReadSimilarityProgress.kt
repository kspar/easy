package core.ems.service.code

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.ems.service.code.similarity.SimilarityService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * How far the caller's own running similarity check has got (EZ-1667), for the page to show while the
 * teacher waits: a check is one request that answers only at the end, and for up to its whole budget -
 * two minutes - there was nothing to see but a greyed button. Polled about once a second, so it reads only
 * what the run keeps in memory, and says nothing of another teacher's check.
 */
@RestController
@RequestMapping("/v2")
class TeacherReadSimilarityProgressController(private val similarityService: SimilarityService) {
    private val log = KotlinLogging.logger {}

    data class Resp(
        /** False when none of the caller's checks is running; the rest is then zero. */
        @get:JsonProperty("running") val running: Boolean,
        /** `preparing` - reading the solutions - or `comparing`; null when nothing is running. */
        @get:JsonProperty("phase") val phase: String?,
        /** Pairs settled - scored, or ruled out - of [total], while comparing. */
        @get:JsonProperty("done") val done: Long,
        @get:JsonProperty("total") val total: Long,
        @get:JsonProperty("elapsed_sec") val elapsedSec: Long,
        @get:JsonProperty("budget_sec") val budgetSec: Long,
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/similarity/progress")
    fun controller(caller: EasyUser): Resp {
        // Debug, not info: the page asks every second for as long as a check runs.
        log.debug { "Similarity progress for ${caller.id}" }
        val progress = similarityService.progress(caller.id) ?: return Resp(false, null, 0, 0, 0, 0)
        return Resp(
            true,
            if (progress.comparing) "comparing" else "preparing",
            progress.done,
            progress.total,
            progress.elapsedSec,
            progress.budgetSec,
        )
    }
}
