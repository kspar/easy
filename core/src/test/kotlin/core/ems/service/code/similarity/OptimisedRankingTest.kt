package core.ems.service.code.similarity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

/**
 * The optimised mode (EZ-1667): pairs a provider's bounds rule out are not scored, the likeliest go
 * first, and a run that reaches its budget returns what it found instead of nothing.
 *
 * The promise to hold it to is that a finished optimised run keeps exactly the pairs the full one keeps,
 * on any number of threads: a bound that came out below the real value, or a floor read wrongly, would
 * drop a pair from the top N without a trace, and the teacher would never see the copy it was.
 */
@Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class OptimisedRankingTest {

    private fun service(timeoutSec: Long = 120, threads: Int = 2, clock: Ticker = Ticker.SYSTEM) =
        SimilarityService(maxSubmissions = 500, maxSolutionTokens = 10_000, maxRepetition = 1_000_000, timeoutSec = timeoutSec, ticker = clock)
            .also { it.threadsFor = { threads } }

    private val editDistance = SimilarityBinding(EditDistanceProvider.ID, SimilarityLanguage.PYTHON)

    /** A solution of [lines] lines built from [seed], names and constants varied, some lines dropped. */
    private fun solution(seed: Int, lines: Int): String {
        val random = Random(seed)
        val names = listOf("total", "count", "x", "value", "acc", "n", "result", "items")
        return buildString {
            appendLine("def solve(data):")
            repeat(lines) { k ->
                val name = names[random.nextInt(names.size)]
                when (random.nextInt(4)) {
                    0 -> appendLine("    $name = $name + ${random.nextInt(100)}")
                    1 -> appendLine("    for i in range(${random.nextInt(10)}, len(data)):")
                    2 -> appendLine("        if data[i] % ${k % 7 + 2} == 0: $name += data[i]")
                    else -> appendLine("    print($name, \"step $k\")")
                }
            }
            appendLine("    return total")
        }
    }

    /** A cohort with real copies in it - verbatim, renamed, padded - among solutions of very different lengths. */
    private fun cohort(seed: Int, size: Int): List<SimilaritySource> {
        val random = Random(seed)
        val originals = (1..size).map { solution(seed * 1000 + it, 5 + random.nextInt(120)) }
        return originals.mapIndexed { index, text ->
            val copied = when {
                index % 9 == 3 -> originals[index - 1]
                index % 9 == 6 -> originals[index - 2].replace("total", "summa")
                index % 9 == 8 -> originals[index - 3] + "\n# extra\n"
                else -> text
            }
            SimilaritySource(index + 1L, copied, "student-${index % (size - 2)}")
        }
    }

    private fun run(sources: List<SimilaritySource>, optimised: Boolean, threads: Int = 2, topN: Int = 10) =
        service(threads = threads).compare({ sources }, { it }, editDistance.copy(optimised = optimised), null, topN).outcome

    private fun signature(outcome: SimilarityOutcome) =
        outcome.pairs.map { "${it.id1}-${it.id2}:${it.similarity}:${it.maxSimilarity}" }

    @Test
    fun `a finished optimised run keeps exactly the pairs the full run keeps, on any number of threads`() {
        for (seed in 1..4) {
            val sources = cohort(seed, 40)
            val full = run(sources, optimised = false)
            for (threads in listOf(1, 2, 4)) {
                val optimised = run(sources, optimised = true, threads = threads)
                assertTrue(optimised.complete)
                assertEquals(signature(full), signature(optimised), "seed $seed on $threads thread(s)")
                assertEquals(full.pairsTotal, optimised.pairsTotal)
            }
        }
    }

    @Test
    fun `pairs the bounds rule out are not scored`() {
        // Lengths from a handful of lines to over a hundred: a short and a long solution cannot be much
        // alike, and once the top N are known, most such pairs are below every floor.
        val outcome = run(cohort(7, 60), optimised = true, topN = 5)
        assertTrue(outcome.pairsCompared < outcome.pairsTotal, "compared ${outcome.pairsCompared} of ${outcome.pairsTotal}")
        // Ruled out but possibly scoring: the result does not claim to have every scoring pair.
        assertTrue(outcome.truncated)
    }

    @Test
    fun `at the budget an optimised run returns the likeliest pairs it reached, and a full one is refused`() {
        // Time is spent as the run reads it, a millisecond a read - about two a pair - so the budget runs out
        // part way on any machine: on real time, one twice as fast as the one this was written on finished
        // the cohort inside it, and the test failed.
        val sources = (1L..60L).map { id -> SimilaritySource(id, solution(id.toInt(), 300), "s$id") }
        val slow = service(timeoutSec = 3, clock = SteppingTicker(1_000_000))
        val outcome = slow.compare({ sources }, { it }, editDistance.copy(optimised = true), null, 10).outcome
        assertFalse(outcome.complete)
        assertTrue(outcome.pairsCompared in 1 until outcome.pairsTotal, "compared ${outcome.pairsCompared} of ${outcome.pairsTotal}")
        assertTrue(outcome.pairs.isNotEmpty())
        assertTrue(outcome.truncated)

        // Full, it is refused - at its pace, as too costly, before the budget is spent on it.
        assertThrows<SimilarityTooCostly> { service(timeoutSec = 1, clock = SteppingTicker(1_000_000)).compare({ sources }, { it }, editDistance, null, 10) }
    }

    /**
     * Edit distance with its bounds withheld: the engine's path for a provider that cannot bound its pairs,
     * which no provider built in takes since JPlag gained bounds, and one over HTTP might.
     */
    private class Unbounded(inner: SimilarityProvider = EditDistanceProvider()) : SimilarityProvider by inner {
        override val id = "unbounded"
        override val bounds = false
    }

    private val unbounded = SimilarityBinding("unbounded", SimilarityLanguage.PYTHON, optimised = true)

    /** Edit distance with its bounds at half what they are: wrong, as a provider's could be after an upgrade. */
    private class Unsound(private val inner: SimilarityProvider = EditDistanceProvider()) : SimilarityProvider by inner {
        override val id = "unsound"

        override fun open(request: ProviderRequest, run: RunContext): ProviderSession {
            val session = inner.open(request, run)
            return object : ProviderSession by session {
                override fun bound(a: Long, b: Long) = session.bound(a, b)?.let { PairBound(it.score / 2, it.rank / 2, it.similarity / 2) }
                override fun quickBound(a: Long, b: Long) = bound(a, b)
            }
        }
    }

    @Test
    fun `a provider caught bounding below a score keeps its result exact, and is not trusted to bound again`() {
        val sources = cohort(2, 40)
        val service = service().also { it.registry = SimilarityRegistry(listOf(Unsound())) }
        val unsound = SimilarityBinding("unsound", SimilarityLanguage.PYTHON, optimised = true)
        val full = service().compare({ sources }, { it }, editDistance, null, 10).outcome
        val caught = service.compare({ sources }, { it }, unsound, null, 10).outcome
        assertEquals(signature(full), signature(caught))
        // Caught once, its next check compares every pair, and is priced as one that does.
        val next = service.compare({ sources }, { it }, unsound, null, 10).outcome
        assertEquals(next.pairsTotal, next.pairsCompared)
        assertFalse(service.estimate(unsound, sources.map { SizedSource(it.id, it.solution.length, it.owner) }).optimised)
    }

    private fun unboundedService(timeoutSec: Long = 120) =
        service(timeoutSec).also { it.registry = SimilarityRegistry(listOf(Unbounded())) }

    @Test
    fun `a provider that cannot bound its pairs compares every pair, optimised or not`() {
        val sources = cohort(3, 12)
        val outcome = unboundedService().compare({ sources }, { it }, unbounded, null, 10).outcome
        assertTrue(outcome.complete)
        assertEquals(outcome.pairsTotal, outcome.pairsCompared)
    }

    private val jplag = SimilarityBinding(JPlagProvider.ID, SimilarityLanguage.PYTHON)

    @Test
    fun `optimised, JPlag keeps exactly the pairs its full run keeps, on any number of threads, and compares fewer`() {
        // Its bounds on the shared token runs; one below the tiling would drop a pair from the top without a
        // trace. Varied lengths, renamed and padded copies: the cohort the edit-distance test above uses.
        for (seed in 1..3) {
            val sources = cohort(seed, 40)
            val full = service().compare({ sources }, { it }, jplag, null, 10).outcome
            for (threads in listOf(1, 2, 4)) {
                val optimised = service(threads = threads).compare({ sources }, { it }, jplag.copy(optimised = true), null, 10).outcome
                assertTrue(optimised.complete)
                assertEquals(signature(full), signature(optimised), "seed $seed on $threads thread(s)")
                assertTrue(optimised.pairsCompared < optimised.pairsTotal, "compared ${optimised.pairsCompared} of ${optimised.pairsTotal}")
            }
        }
    }

    @Test
    fun `optimised, a JPlag run past its budget returns the likeliest pairs it reached, copies first`() {
        // Every tiling takes 200 ms of the test's own clock, so the cohort cannot finish in two seconds of it,
        // however long tokenising takes on the machine: what it reached by then is returned, and the verbatim
        // copies, bounded highest, are among it.
        val sources = cohort(5, 60)
        val clock = ManualTicker()
        val slow = service(timeoutSec = 2, threads = 1, clock = clock)
        slow.comparePair = { gst, first, second -> clock.advance(200); gst.compare(first, second) }
        val outcome = slow.compare({ sources }, { it }, jplag.copy(optimised = true), null, 10).outcome
        assertFalse(outcome.complete)
        assertTrue(outcome.pairsCompared in 1 until outcome.pairsTotal, "compared ${outcome.pairsCompared} of ${outcome.pairsTotal}")
        val copies = sources.indices.filter { it % 9 == 3 }.map { (it + 1L) to it.toLong() }
        val found = outcome.pairs.map { minOf(it.id1, it.id2) to maxOf(it.id1, it.id2) }.toSet()
        assertTrue(copies.any { (copy, original) -> (original to copy) in found }, "no verbatim copy among ${outcome.pairs.size} pairs")
    }

    @Test
    fun `optimised, a cohort over the budget is priced but not refused - unless its provider cannot bound`() {
        // 200 solutions of 5 KB: prepared in seconds by either provider, compared in well over ten.
        val sources = (1L..200L).map { SizedSource(it, 5_000, "s$it") }
        val tenSeconds = service(timeoutSec = 10)
        val estimate = tenSeconds.assertAffordable(editDistance.copy(optimised = true), sources)
        assertTrue(estimate.optimised)
        assertFalse(estimate.withinBudget)
        assertThrows<SimilarityTooCostly> { tenSeconds.assertAffordable(editDistance, sources) }
        assertThrows<SimilarityTooCostly> { unboundedService(timeoutSec = 10).assertAffordable(unbounded, sources) }
        // JPlag bounds its pairs, so it is not refused optimised either.
        val jplagEstimate = tenSeconds.assertAffordable(jplag.copy(optimised = true), sources)
        assertTrue(jplagEstimate.optimised && !jplagEstimate.withinBudget)
    }
}
