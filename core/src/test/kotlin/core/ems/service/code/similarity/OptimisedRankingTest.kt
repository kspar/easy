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

    private fun service(timeoutSec: Long = 120, threads: Int = 2) =
        SimilarityService(maxSubmissions = 500, maxSolutionTokens = 10_000, maxRepetition = 1_000_000, timeoutSec = timeoutSec)
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
        // Long solutions, so that a pair takes milliseconds and the cohort far more than a second.
        val sources = (1L..60L).map { id -> SimilaritySource(id, solution(id.toInt(), 300), "s$id") }
        val slow = service(timeoutSec = 2)
        val outcome = slow.compare({ sources }, { it }, editDistance.copy(optimised = true), null, 10).outcome
        assertFalse(outcome.complete)
        assertTrue(outcome.pairsCompared in 1 until outcome.pairsTotal, "compared ${outcome.pairsCompared} of ${outcome.pairsTotal}")
        assertTrue(outcome.pairs.isNotEmpty())
        assertTrue(outcome.truncated)

        // Full, it is refused - at its pace, as too costly, before the budget is spent on it.
        assertThrows<SimilarityTooCostly> { service(timeoutSec = 2).compare({ sources }, { it }, editDistance, null, 10) }
    }

    @Test
    fun `a provider that cannot bound its pairs compares every pair, optimised or not`() {
        val sources = cohort(3, 12)
        val jplag = SimilarityBinding(JPlagProvider.ID, SimilarityLanguage.PYTHON, optimised = true)
        val outcome = service().compare({ sources }, { it }, jplag, null, 10).outcome
        assertTrue(outcome.complete)
        assertEquals(outcome.pairsTotal, outcome.pairsCompared)
    }

    @Test
    fun `optimised, a cohort over the budget is priced but not refused - unless its provider cannot bound`() {
        val sources = listOf(SizedSource(1, 100, "ann"), SizedSource(2, 100, "ben"))
        val noBudget = service(timeoutSec = 0)
        val estimate = noBudget.assertAffordable(editDistance.copy(optimised = true), sources)
        assertTrue(estimate.optimised)
        assertFalse(estimate.withinBudget)
        assertThrows<SimilarityTooCostly> { noBudget.assertAffordable(editDistance, sources) }
        val jplag = SimilarityBinding(JPlagProvider.ID, SimilarityLanguage.PYTHON, optimised = true)
        assertThrows<SimilarityTooCostly> { noBudget.assertAffordable(jplag, sources) }
    }
}
