package core.ems.service.code.similarity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Pricing a cohort before it runs (EZ-1667): which pairs the estimate counts, and how its time becomes
 * a refusal. It must count the pairs the engine would compare - more, and a run that would finish is
 * refused; fewer, and one that cannot is admitted to wait out the whole budget.
 */
class SimilarityEstimateTest {

    private fun service(timeoutSec: Long = 120, threads: Int = 1) =
        SimilarityService(maxSubmissions = 500, maxSolutionTokens = 10_000, maxRepetition = 1_000_000, timeoutSec = timeoutSec)
            .also { it.threadsFor = { threads } }

    private val editDistance = SimilarityBinding(EditDistanceProvider.ID, SimilarityLanguage.PYTHON)
    private val jplag = SimilarityBinding(JPlagProvider.ID, SimilarityLanguage.PYTHON)
    private val provider = EditDistanceProvider()

    @Test
    fun `a student's own submissions are not a pair, and everyone else's are`() {
        val sources = listOf(SizedSource(1, 100, "ann"), SizedSource(2, 100, "ann"), SizedSource(3, 100, "ben"), SizedSource(4, 100, "cid"))
        // Six pairs among four, less ann against herself.
        assertEquals(5, service().estimate(editDistance, sources).pairs)
        // An unknown author is nobody's own attempt.
        assertEquals(6, service().estimate(editDistance, sources.map { it.copy(owner = null) }).pairs)
    }

    @Test
    fun `identical texts are compared once, as the engine compares a group once`() {
        val sources = listOf(
            SizedSource(1, 200, "ann", "same"), SizedSource(2, 200, "ben", "same"), SizedSource(3, 200, "cid", "same"),
            SizedSource(4, 300, "dan", "other"),
        )
        val estimate = service().estimate(editDistance, sources)
        assertEquals(1, estimate.pairs)
        // Every submission is still prepared; only the one pair is scored.
        assertEquals(sources.sumOf { provider.prepareNanos(it.chars) } + provider.pairNanos(200, 300), estimate.wallNanos)
    }

    @Test
    fun `identical texts by fewer than three authors are each compared, as the engine compares them`() {
        // The engine groups identical submissions only at three authors; two copies stay two comparands,
        // each against everyone. Collapsed here, fifty such pairs priced at a quarter of their cost.
        val sources = listOf(
            SizedSource(1, 200, "ann", "same"), SizedSource(2, 200, "ben", "same"),
            SizedSource(3, 300, "cid", "other"), SizedSource(4, 300, "cid", "other"),
        )
        // ann, ben, cid, cid: six pairs, less cid's against cid's own.
        assertEquals(5, service().estimate(editDistance, sources).pairs)
    }

    @Test
    fun `a solution the provider skips for its size is in no pair`() {
        val sources = listOf(SizedSource(1, 0), SizedSource(2, 50_000), SizedSource(3, 100), SizedSource(4, 120))
        assertEquals(1, service().estimate(editDistance, sources).pairs)
        // JPlag has no character cap, so the same cohort is three pairs there: only the blank one is out.
        assertEquals(3, service().estimate(jplag, sources).pairs)
    }

    @Test
    fun `the time is the pool's, not one thread's`() {
        val sources = (1L..20L).map { SizedSource(it, 5_000, "s$it") }
        val one = service(threads = 1).estimate(editDistance, sources)
        val four = service(threads = 4).estimate(editDistance, sources)
        assertEquals(one.pairs, four.pairs)
        assertEquals(one.wallNanos / 4, four.wallNanos)
        assertEquals(4, four.threads)
    }

    @Test
    fun `the calibration holds where it was measured, 500 solutions of 2_7 KB in about 100 s on one thread`() {
        // The measurement EditDistanceProvider.pairNanos is fitted to; a constant edited past it shows here.
        val estimate = service(timeoutSec = 1_000).estimate(editDistance, (1L..500L).map { SizedSource(it, 2_700, "s$it") })
        assertEquals(124_750, estimate.pairs)
        assertTrue(estimate.seconds in 70..130, "estimated ${estimate.seconds}s against 100 s measured")
    }

    @Test
    fun `optimised, a cohort whose preparing alone outlasts the budget is refused, as nothing would be found by then`() {
        // JPlag bounds its pairs, so optimised it stops at the budget with what it reached - after tokenising
        // the cohort, which is not stopped part way with anything to show. 500 solutions at the submit limit
        // of 500,000 characters are priced at some 128 s to tokenise on four threads: past the two minutes
        // before any pair.
        val optimised = jplag.copy(optimised = true)
        val huge = (1L..500L).map { SizedSource(it, 500_000, "s$it") }
        val estimate = service(threads = 4).estimate(optimised, huge)
        assertFalse(estimate.optimised, "priced as stopping at the budget with something found")
        assertThrows<SimilarityTooCostly> { service(threads = 4).assertAffordable(optimised, huge) }
        // Ordinary solutions prepare in moments, and the same cohort's pairs past the budget are not refused.
        val ordinary = (1L..500L).map { SizedSource(it, 19_000, "s$it") }
        assertTrue(service(threads = 4).assertAffordable(optimised, ordinary).optimised)
    }

    @Test
    fun `JPlag prices a pair of large solutions at what tiling them costs, and an ordinary pair near what it costs`() {
        // Measured in the EZ-1667 stress test, on one of four threads: 21-29 ms a pair of 19 KB solutions,
        // which a model linear in their sizes priced at 0.2 ms - 300 of them at 7 s, against over 120 s.
        val large = JPlagProvider(maxSolutionTokens = 10_000, maxRepetition = 1_000_000).pairNanos(19_000, 19_000)
        assertTrue(large in 20_000_000L..40_000_000L, "a 19 KB pair priced at ${large / 1_000} µs")
        // 0.07 ms measured at 0.8 KB: generous, but not so much that an ordinary cohort is refused.
        val ordinary = JPlagProvider(maxSolutionTokens = 10_000, maxRepetition = 1_000_000).pairNanos(800, 800)
        assertTrue(ordinary in 60_000L..200_000L, "a 0.8 KB pair priced at ${ordinary / 1_000} µs")
        // 300 of the large ones on four threads: over the two-minute budget, so refused before they run.
        val estimate = service(threads = 4).estimate(jplag, (1L..300L).map { SizedSource(it, 19_000, "s$it") })
        assertTrue(!estimate.withinBudget, "300 large solutions estimated at ${estimate.seconds}s")
    }

    @Test
    fun `over the budget is refused, and the budget itself is not`() {
        val sources = listOf(SizedSource(1, 100, "ann"), SizedSource(2, 100, "ben"))
        val wall = service().estimate(editDistance, sources).wallNanos
        assertTrue(wall in 1..1_000_000_000L, "a tiny cohort is priced at $wall ns")
        assertTrue(service(timeoutSec = 1).estimate(editDistance, sources).withinBudget)

        val refused = assertThrows<SimilarityTooCostly> { service(timeoutSec = 0).assertAffordable(editDistance, sources) }
        assertFalse(refused.estimate.withinBudget)
        // Rounded up: under a second is "about 1 s", never "about 0 s".
        assertEquals(1, refused.estimate.seconds)
        assertEquals(0, refused.estimate.budgetSec)
    }

    @Test
    fun `whole seconds round up, and exactly the budget is within it`() {
        val exactly = SimilarityEstimate(submissions = 2, pairs = 1, threads = 1, wallNanos = 2_000_000_000L, budgetSec = 2)
        assertTrue(exactly.withinBudget)
        assertEquals(2, exactly.seconds)
        val over = exactly.copy(wallNanos = 2_000_000_001L)
        assertFalse(over.withinBudget)
        assertEquals(3, over.seconds)
    }
}
