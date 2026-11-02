package core.ems.service.code.similarity

import core.exception.InvalidRequestException
import core.exception.ReqError
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Which submissions of a cohort are most alike, entirely in memory.
 *
 * The exercise's language decides how: Python by [JPlagProvider], which compares tokens, so renaming and
 * reformatting do not hide a copy; everything else - SQL, text - by [EditDistanceProvider], character by
 * character. This class admits one comparison at a time, bounds how long it may run, groups identical
 * submissions, and keeps the best pairs, the same for both providers.
 */
@Service
class SimilarityService(
    /**
     * How long one check may run, in seconds. Read before each solution is tokenised and before each pair
     * is compared, so a check past it stops there; what is already running finishes, which is why each
     * provider also bounds what one solution and one pair can cost.
     */
    @param:Value("\${easy.core.similarity.timeout-sec:120}") private val timeoutSec: Long,
) {
    private val log = KotlinLogging.logger {}

    private val jplag = JPlagProvider()
    private val editDistance = EditDistanceProvider()

    /** The provider for an exercise's language; replaceable only by a test. */
    internal var providerFor: (SimilarityLanguage) -> SimilarityProvider =
        { language -> if (language == SimilarityLanguage.PYTHON) jplag else editDistance }

    /** One comparison at a time: two large cohorts at once is the realistic way to exhaust the heap. */
    private val oneAtATime = Semaphore(1)

    /** The check running now, for its caller to watch; see [progress]. */
    private val running = AtomicReference<RunProgress?>()

    private class RunProgress(val owner: String, val started: Long) {
        @Volatile
        var ranking: PairRanking? = null
    }

    /** What [owner]'s running check has done, or null when none of theirs is running. */
    fun progress(owner: String): SimilarityProgress? {
        val run = running.get()?.takeIf { it.owner == owner } ?: return null
        val elapsedSec = (System.nanoTime() - run.started) / 1_000_000_000
        val ranking = run.ranking ?: return SimilarityProgress(false, 0, 0, elapsedSec, timeoutSec)
        return SimilarityProgress(true, minOf(ranking.done.get(), ranking.total), ranking.total, elapsedSec, timeoutSec)
    }

    /**
     * Compares the sources [loadSources] returns, in [language].
     *
     * They are loaded only once this caller holds the permit, so a burst of clicks loads one cohort, not one
     * each. A check while another runs is refused at once. Nothing of the comparison is still running when
     * this returns or throws.
     */
    fun compare(
        loadSources: () -> List<SimilaritySource>,
        language: SimilarityLanguage,
        topN: Int,
        /** Who may watch the run's [progress]. */
        owner: String,
    ): SimilarityOutcome {
        val provider = providerFor(language)
        if (!oneAtATime.tryAcquire()) {
            throw InvalidRequestException(
                "Another similarity comparison is already running, try again shortly.",
                ReqError.SIMILARITY_BUSY, notify = false,
            )
        }
        // Nothing between the permit and the try: whatever throws there would keep the permit for good.
        try {
            val progress = RunProgress(owner, System.nanoTime())
            running.set(progress)
            // From the permit, as the page's clock counts: loading the sources is part of the check.
            val deadline = Deadline.after(TimeUnit.SECONDS.toNanos(timeoutSec))
            return run(provider, loadSources(), language, topN, deadline, progress)
        } catch (_: SimilarityTimedOut) {
            throw InvalidRequestException(
                "Similarity comparison did not finish within ${timeoutSec}s; try a smaller group.",
                ReqError.SIMILARITY_TIMEOUT,
                "budget_sec" to timeoutSec.toString(),
                notify = false,
            )
        } finally {
            running.set(null)
            oneAtATime.release()
        }
    }

    private fun run(
        provider: SimilarityProvider,
        sources: List<SimilaritySource>,
        language: SimilarityLanguage,
        topN: Int,
        deadline: Deadline,
        progress: RunProgress,
    ): SimilarityOutcome {
        if (sources.size < 2) return SimilarityOutcome(provider.id, language, emptyList(), emptyList(), emptyList(), false)
        // Half the cores: this runs inside the process that serves every other request.
        val threads = minOf(Runtime.getRuntime().availableProcessors() / 2, sources.size).coerceAtLeast(1)
        val started = System.nanoTime()

        SimilarityWorkers(threads, deadline).use { workers ->
            val cohort = provider.prepare(sources, language, workers)
            if (cohort.usable.size < 2) {
                return SimilarityOutcome(provider.id, cohort.language, emptyList(), cohort.skipped, emptyList(), false)
            }

            val ownerById = sources.associate { it.id to it.owner }
            val comparands = comparands(cohort.usable, sources.associate { it.id to it.solution }, ownerById)
            val ranking = PairRanking(workers)
            progress.ranking = ranking
            val ranked = ranking.rank(cohort, comparands, topN)

            val comparandById = comparands.associateBy { it.representative }
            val pairs = ranked.top.map { shown(it, comparandById, ownerById) }
            val groups = comparands.filter { it.members.size > 1 }.map { it.members }
            log.info {
                "Compared ${cohort.usable.size} submissions (${cohort.language.wireName}, ${provider.id}) in " +
                    "${(System.nanoTime() - started) / 1_000_000}ms on $threads thread(s), ${cohort.skipped.size} skipped, " +
                    "${groups.size} identical group(s), ${ranked.scoring} scoring pairs, returning ${pairs.size}"
            }
            return SimilarityOutcome(provider.id, cohort.language, pairs, cohort.skipped, groups, ranked.scoring > pairs.size)
        }
    }

    /**
     * The cohort as it is compared: each submission on its own, except that one text handed in by at least
     * [GROUP_AUTHORS] different authors is one [Comparand], compared once and reported as a group. Twelve
     * students handing in the starter code unchanged otherwise made 66 rows at 100% among themselves. Two
     * identical submissions stay a pair, which the page shows side by side.
     */
    private fun comparands(usable: List<Long>, solutionById: Map<Long, String>, ownerById: Map<Long, String>): List<Comparand> {
        val byText = LinkedHashMap<String, MutableList<Long>>()
        for (id in usable) byText.getOrPut(solutionById.getValue(id)) { mutableListOf() } += id
        return byText.values.flatMap { same ->
            if (same.map(ownerById::getValue).distinct().size >= GROUP_AUTHORS) {
                val members = same.sorted()
                listOf(Comparand(members, members.map(ownerById::getValue)))
            } else {
                same.map { Comparand(listOf(it), listOf(ownerById.getValue(it))) }
            }
        }
    }

    /**
     * A ranked pair as the two submissions to show: the comparands' first members, or where those have one
     * author - a student's own attempt inside a group against another - the first two whose authors differ.
     * A group's members are the same text, so the pair's matches hold for any of them.
     */
    private fun shown(pair: SimilarityPair, comparandById: Map<Long, Comparand>, ownerById: Map<Long, String>): SimilarityPair {
        val first = comparandById.getValue(pair.id1)
        val second = comparandById.getValue(pair.id2)
        val (id1, id2) = first.members.asSequence()
            .flatMap { a -> second.members.asSequence().map { b -> a to b } }
            .first { (a, b) -> ownerById.getValue(a) != ownerById.getValue(b) }
        return pair.copy(id1 = id1, id2 = id2)
    }

    private companion object {
        /** How many different authors of one text make it a group; see [comparands]. */
        const val GROUP_AUTHORS = 3
    }
}
