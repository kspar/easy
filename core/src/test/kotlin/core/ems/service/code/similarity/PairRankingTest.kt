package core.ems.service.code.similarity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * [PairRanking.rankBounded] against [PairRanking.rank], over a session whose numbers are made up.
 *
 * Made up because the real providers hide what is under test. Edit distance's two orders nearly agree -
 * the higher of two measures and their mean rank pairs almost alike - so a floor read wrongly in one
 * order changed nothing; here score and rank are drawn apart, as JPlag's percentage and shared tokens
 * are. And a provider's timeout lands wherever its pairs happen to be; here it lands after a known pair.
 */
class PairRankingTest {

    /** Pairs with independent score and rank, bounded exactly - the tightest a bound can be, so the most is ruled out. */
    private class Drawn(
        private val seed: Int,
        private val failAfter: Int = Int.MAX_VALUE,
        private val boundMs: Long = 0,
    ) : ProviderSession {
        val calls = AtomicInteger()
        val bounds = AtomicInteger()

        /** Drawn from the pair itself, so that which worker asks first changes nothing. */
        private fun of(a: Long, b: Long): Pair<Double, Double> {
            val random = Random(seed * 1_000_003L + minOf(a, b) * 10_000 + maxOf(a, b))
            return random.nextDouble() to random.nextDouble() * 500
        }

        override fun prepare() = error("not used")
        override fun ready(representatives: List<Long>) = Unit
        override fun details(pair: PairScore, shownFirst: Long, shownSecond: Long) = emptyList<SimilarityMatch>()
        override fun metrics(pair: PairScore) = emptyList<SimilarityMetric>()

        override fun score(a: Long, b: Long): PairScore {
            // As a provider polling its deadline in the middle of a pair would.
            if (calls.incrementAndGet() > failAfter) throw SimilarityTimedOut(1)
            val (score, rank) = of(a, b)
            return PairScore(minOf(a, b), maxOf(a, b), score, rank, score / 2, null)
        }

        override fun bound(a: Long, b: Long): PairBound {
            bounds.incrementAndGet()
            if (boundMs > 0) Thread.sleep(boundMs)
            val (score, rank) = of(a, b)
            return PairBound(score, rank, score / 2)
        }

        /** Looser than [bound], as a quick bound is: the pair's own values, raised. */
        override fun quickBound(a: Long, b: Long): PairBound {
            val (score, rank) = of(a, b)
            return PairBound(minOf(1.0, score + 0.2), rank + 100, minOf(1.0, score + 0.2) / 2)
        }
    }

    private fun comparands(n: Int) = (1L..n).map { Comparand(it, listOf(it), listOf("s$it")) }

    private fun <R> ranking(threads: Int, budgetMs: Long = 60_000, block: (PairRanking) -> R): R =
        SimilarityWorkers(threads, 1L shl 20).use { block(PairRanking(it, Deadline.after(TimeUnit.MILLISECONDS.toNanos(budgetMs)), 60)) }

    private fun ids(ranking: Ranking) = ranking.top.map { "${it.lowId}-${it.highId}" }

    @Test
    fun `with score and rank apart, the bounded ranking keeps exactly what the full one keeps`() {
        for (seed in 1..5) for (threads in listOf(1, 3)) {
            val full = ranking(threads) { it.rank(Drawn(seed), comparands(40), 5) }
            val bounded = ranking(threads) { it.rankBounded(Drawn(seed), comparands(40), 5, partial = false) }
            assertEquals(ids(full), ids(bounded), "seed $seed on $threads thread(s)")
            assertEquals(780, bounded.total)
        }
    }

    @Test
    fun `pairs the quick bound already rules out are never bounded exactly`() {
        // The quick bounds order everything for free; the exact ones are for the pairs reached.
        val session = Drawn(seed = 4)
        val ranked = ranking(2) { it.rankBounded(session, comparands(40), 3, partial = false) }
        assertTrue(session.bounds.get() < ranked.total, "${session.bounds.get()} exact bounds for ${ranked.total} pairs")
    }

    @Test
    fun `a slow exact bound is stopped by the deadline like a pair is, and what was found is returned`() {
        // Bounded exactly up front, a phase of its own, this ran out of time before scoring a single pair -
        // and that phase threw, partial or not.
        // A worker's share of a batch is 32 pairs, 1.6 s of bounds here: stopped only between batches, the
        // run would outlast its 300 ms by more than a second.
        val session = Drawn(seed = 6, boundMs = 50)
        val started = System.nanoTime()
        val stopped = ranking(2, budgetMs = 300) { it.rankBounded(session, comparands(30), 5, partial = true) }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertFalse(stopped.complete)
        assertTrue(stopped.compared in 1 until stopped.total, "compared ${stopped.compared} of ${stopped.total}")
        assertTrue(tookMs < 1_200, "stopped only after ${tookMs}ms")
    }

    @Test
    fun `a timeout in the middle of a pair keeps the pairs each worker finished before it`() {
        // One worker, so the five pairs before the sixth are one chunk's: thrown away with the chunk, a
        // stopped run returned nothing it had scored.
        val session = Drawn(seed = 9, failAfter = 5)
        val stopped = ranking(1) { it.rankBounded(session, comparands(20), 10, partial = true) }
        assertFalse(stopped.complete)
        assertEquals(5, stopped.compared)
        assertEquals(5, stopped.top.size)
    }
}
