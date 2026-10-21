package core.ems.service.code.similarity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    private class Drawn(private val seed: Int, private val failAfter: Int = Int.MAX_VALUE) : ProviderSession {
        val calls = AtomicInteger()

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
            val (score, rank) = of(a, b)
            return PairBound(score, rank, score / 2)
        }
    }

    private fun comparands(n: Int) = (1L..n).map { Comparand(it, listOf(it), listOf("s$it")) }

    private fun <R> ranking(threads: Int, block: (PairRanking) -> R): R =
        SimilarityWorkers(threads, 1L shl 20).use { block(PairRanking(it, Deadline.after(TimeUnit.SECONDS.toNanos(60)), 60)) }

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
