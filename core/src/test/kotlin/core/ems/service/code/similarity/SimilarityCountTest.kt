package core.ems.service.code.similarity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Counting a cohort before it runs (EZ-1667): which pairs the page says Find would compare. It must count
 * the pairs the engine compares - the count before Find and the one after it are on one page, and two
 * numbers for one thing is a question the teacher cannot answer.
 */
class SimilarityCountTest {

    private fun service() = SimilarityService(maxSubmissions = 500, maxSolutionTokens = 10_000, maxRepetition = 1_000_000, timeoutSec = 120)

    private val editDistance = SimilarityBinding(EditDistanceProvider.ID, SimilarityLanguage.PYTHON)
    private val jplag = SimilarityBinding(JPlagProvider.ID, SimilarityLanguage.PYTHON)

    @Test
    fun `a student's own submissions are not a pair, and everyone else's are`() {
        val sources = listOf(SizedSource(1, 100, "ann"), SizedSource(2, 100, "ann"), SizedSource(3, 100, "ben"), SizedSource(4, 100, "cid"))
        // Six pairs among four, less ann against herself.
        assertEquals(SimilarityCohort(submissions = 4, pairs = 5, notCompared = 0), service().count(editDistance, sources))
        // An unknown author is nobody's own attempt.
        assertEquals(6, service().count(editDistance, sources.map { it.copy(owner = null) }).pairs)
    }

    @Test
    fun `identical texts are compared once, as the engine compares a group once`() {
        val sources = listOf(
            SizedSource(1, 200, "ann", "same"), SizedSource(2, 200, "ben", "same"), SizedSource(3, 200, "cid", "same"),
            SizedSource(4, 300, "dan", "other"),
        )
        assertEquals(SimilarityCohort(submissions = 4, pairs = 1, notCompared = 0), service().count(editDistance, sources))
    }

    @Test
    fun `identical texts by fewer than three authors are each compared, as the engine compares them`() {
        // The engine groups identical submissions only at three authors; two copies stay two comparands,
        // each against everyone. Collapsed here, fifty such pairs were counted as a quarter of what ran.
        val sources = listOf(
            SizedSource(1, 200, "ann", "same"), SizedSource(2, 200, "ben", "same"),
            SizedSource(3, 300, "cid", "other"), SizedSource(4, 300, "cid", "other"),
        )
        // ann, ben, cid, cid: six pairs, less cid's against cid's own.
        assertEquals(5, service().count(editDistance, sources).pairs)
    }

    @Test
    fun `a blank solution is in no pair, and the submissions in none are counted`() {
        // Whitespace only is skipped as too short by every provider; counted, it made pairs the run skipped,
        // and the count before Find disagreed with the one after it.
        val sources = listOf(SizedSource(1, 100, "ann"), SizedSource(2, 120, "ben"), SizedSource(3, 4, "cid", blank = true), SizedSource(4, 50_000, "dan"))
        assertEquals(SimilarityCohort(submissions = 4, pairs = 1, notCompared = 2), service().count(editDistance, sources))
        assertEquals(1, service().count(jplag, sources).notCompared)
    }

    @Test
    fun `a solution the provider skips for its size is in no pair`() {
        val sources = listOf(SizedSource(1, 0), SizedSource(2, 50_000), SizedSource(3, 100), SizedSource(4, 120))
        assertEquals(1, service().count(editDistance, sources).pairs)
        // JPlag has no character cap, so the same cohort is three pairs there: only the blank one is out.
        assertEquals(3, service().count(jplag, sources).pairs)
    }

    @Test
    fun `a refusal's whole seconds round up, so a run is never said to take none`() {
        val exactly = SimilarityProjection(wallNanos = 2_000_000_000L, budgetSec = 2, preparing = false)
        assertEquals(2, exactly.seconds)
        assertEquals(3, exactly.copy(wallNanos = 2_000_000_001L).seconds)
        assertEquals(1, exactly.copy(wallNanos = 1L).seconds)
    }
}
