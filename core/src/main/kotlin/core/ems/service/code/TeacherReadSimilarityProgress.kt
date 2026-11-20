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
 * How far the caller's own running similarity check has got, for the page to show while they wait: a check
 * answers only at the end, up to its whole budget later. Polled about once a second, so it reads only what
 * the run keeps in memory, and says nothing of another teacher's check.
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
        /** Pairs compared so far, of [total]. */
        @get:JsonProperty("done") val done: Long,
        @get:JsonProperty("total") val total: Long,
        @get:JsonProperty("elapsed_sec") val elapsedSec: Long,
        @get:JsonProperty("budget_sec") val budgetSec: Long,
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/similarity/progress")
    fun controller(caller: EasyUser): Resp {
        // Debug: the page asks every second while a check runs.
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
