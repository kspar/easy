package core.ems.service.code.similarity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
        private val scoreMs: Long = 0,
        /** Only the first this many pairs take [scoreMs]: a cold start. */
        private val slowPairs: Int = Int.MAX_VALUE,
        /** What a pair takes on [clock] besides [scoreMs], by its two ids: pairs that differ in cost. */
        private val pairMs: (Long, Long) -> Long = { _, _ -> 0 },
        /** A quick bound that is the exact one, as JPlag's is. */
        private val quickIsExact: Boolean = false,
        /** The clock [scoreMs] and [boundMs] are spent on. */
        private val clock: ManualTicker = ManualTicker(),
        /** Bounds at half the real values: wrong, as a provider's bounds could be after an upgrade. */
        private val unsound: Boolean = false,
        /**
         * Exact bounds a hair below the real values for pairs less alike than this, and sound for the rest:
         * the pairs reached first, so a run has set its floors and ruled pairs out before one is caught.
         */
        private val unsoundBelow: Double = 0.0,
        /** Bounds of nothing for the pairs scoring above this, sound for the rest: wrong exactly on pairs they rule out. */
        private val hiddenAbove: Double = 2.0,
    ) : ProviderSession {
        override val quickBoundIsExact get() = quickIsExact

        val calls = AtomicInteger()
        val bounds = AtomicInteger()

        /**
         * Score, rank and similarity, drawn from the pair itself, so that which worker asks first changes
         * nothing, and each on its own: no order of one is an order of another.
         */
        fun of(a: Long, b: Long): Triple<Double, Double, Double> {
            val random = Random(seed * 1_000_003L + minOf(a, b) * 10_000 + maxOf(a, b))
            return Triple(random.nextDouble(), random.nextDouble() * 500, random.nextDouble())
        }

        override fun prepare() = error("not used")
        override fun ready(representatives: List<Long>) = Unit
        override fun details(pair: PairScore, shownFirst: Long, shownSecond: Long) = emptyList<SimilarityMatch>()
        override fun metrics(pair: PairScore) = emptyList<SimilarityMetric>()

        override fun score(a: Long, b: Long): PairScore {
            // As a provider polling its deadline in the middle of a pair would.
            if (calls.incrementAndGet() > failAfter) throw SimilarityTimedOut(1)
            if (scoreMs > 0 && calls.get() <= slowPairs) clock.advance(scoreMs)
            clock.advance(pairMs(a, b))
            val (score, rank, similarity) = of(a, b)
            return PairScore(minOf(a, b), maxOf(a, b), score, rank, similarity, null)
        }

        override fun bound(a: Long, b: Long): PairBound {
            bounds.incrementAndGet()
            if (boundMs > 0) clock.advance(boundMs)
            val (score, rank, similarity) = of(a, b)
            if (unsound) return PairBound(score / 2, rank / 2, similarity / 2)
            if (similarity < unsoundBelow) return PairBound(score - 0.001, rank - 0.001, similarity - 0.001)
            if (score > hiddenAbove) return PairBound(0.0, 0.0, 0.0)
            return PairBound(score, rank, similarity)
        }

        /** Looser than [bound], as a quick bound is: the pair's own values, raised - the similarity without ties at 1. */
        override fun quickBound(a: Long, b: Long): PairBound {
            val (score, rank, similarity) = of(a, b)
            if (unsound) return PairBound(score / 2, rank / 2, similarity / 2)
            if (score > hiddenAbove) return PairBound(0.0, 0.0, 0.0)
            if (quickIsExact) return PairBound(score, rank, similarity)
            return PairBound(minOf(1.0, score + 0.2), rank + 100, 0.5 + similarity / 2)
        }
    }

    private fun comparands(n: Int, authors: Int = n) = (1L..n).map { Comparand(it, listOf(it), listOf("s${it % authors}")) }

    private fun <R> ranking(threads: Int, budgetMs: Long = 60_000, clock: Ticker = ManualTicker(), block: (PairRanking) -> R): R =
        SimilarityWorkers(threads, 1L shl 20).use { block(PairRanking(it, Deadline.after(TimeUnit.MILLISECONDS.toNanos(budgetMs), clock), budgetMs / 1_000)) }

    private fun ids(ranking: Ranking) = ranking.top.map { "${it.lowId}-${it.highId}" }

    @Test
    fun `with score and rank apart, the bounded ranking keeps exactly what the full one keeps`() {
        for (seed in 1..5) for (threads in listOf(1, 3)) {
            val full = ranking(threads) { it.rank(Drawn(seed), comparands(40), 5) }
            val bounded = ranking(threads) { it.rankBounded(Drawn(seed), comparands(40), 5, partial = false) }
            assertEquals(ids(full), ids(bounded), "seed $seed on $threads thread(s)")
            assertEquals(780, bounded.total)
            assertEquals(null, bounded.boundsViolation)
        }
    }

    @Test
    fun `bounds caught below a score are not trusted, and the run's result is exact`() {
        // Half the real values: the first pair scored is above its bound, and pairs that would be ruled out
        // after it may belong in the top. Ruled out as before, the top was wrong.
        for (threads in listOf(1, 3)) {
            val full = ranking(threads) { it.rank(Drawn(seed = 7), comparands(40), 5) }
            val caught = ranking(threads) { it.rankBounded(Drawn(seed = 7, unsound = true), comparands(40), 5, partial = false) }
            assertEquals(ids(full), ids(caught), "on $threads thread(s)")
            assertTrue(caught.boundsViolation != null)
            assertEquals(780, caught.compared)
        }
    }

    @Test
    fun `bounds caught wrong part way through a run have every pair they passed over scored after all`() {
        // Sound for the pairs reached first, so the floors are set and pairs ruled out before the first wrong
        // bound is caught: by a bound no more trustworthy, so any of them could belong in the top. Only the
        // pairs after it were scored, and the run came back short of those before.
        for (threads in listOf(1, 3)) {
            val full = ranking(threads) { it.rank(Drawn(seed = 7), comparands(40), 5) }
            val caught = ranking(threads) { it.rankBounded(Drawn(seed = 7, unsoundBelow = 0.75), comparands(40), 5, partial = false) }
            assertEquals(ids(full), ids(caught), "on $threads thread(s)")
            assertTrue(caught.boundsViolation != null, "on $threads thread(s)")
            assertEquals(780, caught.compared, "on $threads thread(s)")
        }
    }

    @Test
    fun `bounds wrong only on the pairs they rule out are caught by scoring some of those anyway`() {
        // Nothing for the pairs that score highest, sound for the rest: every pair the bounds let through
        // scores under them, so checking those alone never caught it, and the run left the best pairs out.
        for (threads in listOf(1, 3)) {
            val full = ranking(threads) { it.rank(Drawn(seed = 7), comparands(40), 5) }
            val caught = ranking(threads) { it.rankBounded(Drawn(seed = 7, hiddenAbove = 0.8), comparands(40), 5, partial = false) }
            assertEquals(ids(full), ids(caught), "on $threads thread(s)")
            assertTrue(caught.boundsViolation != null, "on $threads thread(s)")
        }
    }

    @Test
    fun `pairs the quick bound already rules out are not bounded exactly, but for the few audited`() {
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
        val clock = ManualTicker()
        val session = Drawn(seed = 6, boundMs = 50, clock = clock)
        val stopped = ranking(2, budgetMs = 300, clock = clock) { it.rankBounded(session, comparands(30), 5, partial = true) }
        assertFalse(stopped.complete)
        assertTrue(stopped.compared in 1 until stopped.total, "compared ${stopped.compared} of ${stopped.total}")
        // Past the 300 ms by at most one bound a worker had started before it.
        assertTrue(clock.millis <= 300 + 2 * 50, "stopped only after ${clock.millis}ms")
    }

    @Test
    fun `pairs are reached in the order of the share they could have in common, so a run stopped early has the likeliest`() {
        // Not the score's bound - edit distance's is about sizes alone, near 1 for every pair of like length -
        // nor what a pair could share, which for JPlag is a count of tokens and put large unrelated programs
        // before a short copy. Ordered by either, a run stopped after five pairs missed those most alike.
        val drawn = Drawn(seed = 9)
        val likeliest = (1L..20L).flatMap { a -> (a + 1..20L).map { b -> a to b } }
            .sortedByDescending { (a, b) -> drawn.of(a, b).third }.take(5).map { (a, b) -> "$a-$b" }.toSet()
        val stopped = ranking(1) { it.rankBounded(Drawn(seed = 9, failAfter = 5), comparands(20), 10, partial = true) }
        assertEquals(likeliest, ids(stopped).toSet())
    }

    @Test
    fun `a full run its own pace says cannot finish is refused early, as too costly, not at the deadline`() {
        // 190 pairs at 20 ms on one thread is about 4 s against a budget of one: priced too low, the run
        // used to hold the permit the whole second and then time out. Measured at a tenth of it, it stops.
        val clock = ManualTicker()
        val refused = assertThrows<SimilarityTooCostly> {
            ranking(1, budgetMs = 1_000, clock = clock) { it.rank(Drawn(seed = 3, scoreMs = 20, clock = clock), comparands(20), 5) }
        }
        assertTrue(clock.millis < 200, "refused only after ${clock.millis}ms")
        // Five pairs in the tenth of a second it read, so 190 in 3.8 s: the whole run's, as pairs were measured.
        assertEquals(SimilarityProjection(3_800_000_000L, 1, preparing = false), refused.projection)
    }

    @Test
    fun `a slow start is not taken for the pace of the whole run`() {
        // A cold JVM's first pairs are the slowest: three at 30 ms, then the rest at once. Read at the first
        // pair, that pace was 190 pairs at 30 ms against a budget of one second, and the run was refused;
        // read once a tenth of the budget is spent, it is what it is.
        val clock = ManualTicker()
        val ranked = ranking(1, budgetMs = 1_000, clock = clock) { it.rank(Drawn(seed = 3, scoreMs = 30, slowPairs = 3, clock = clock), comparands(20), 5) }
        assertEquals(190, ranked.compared)
    }

    @Test
    fun `the pace counts every pair but an author's own, as the stripes compare them`() {
        // 20 comparands by 5 authors, four each: 190 pairs less 5 times the 6 among each author's own.
        val refused = assertThrows<SimilarityTooCostly> {
            val clock = ManualTicker()
            ranking(1, budgetMs = 1_000, clock = clock) { it.rank(Drawn(seed = 3, scoreMs = 20, clock = clock), comparands(20, authors = 5), 5) }
        }
        // Five pairs in the tenth of a second it read, so 160 in 3.2 s; counted as 190, it said 3.8.
        assertEquals(3_200_000_000L, refused.projection.wallNanos)
    }

    @Test
    fun `a full run's pace reads a fair share of its pairs, so large submissions first do not make one that fits look long`() {
        // Submissions 1-3 first in the cohort's order, every pair of theirs 10 ms of the test's clock and every
        // other pair 1 ms: 1,770 pairs in about 3.3 s against a budget of ten. Walked in that order, every pair
        // of the first two was all the pace had seen a tenth of the way in, and it refused the run as taking 18 s.
        val clock = ManualTicker()
        val large = setOf(1L, 2L, 3L)
        val session = Drawn(seed = 3, clock = clock, pairMs = { a, b -> if (a in large || b in large) 10 else 1 })
        val ranked = ranking(1, budgetMs = 10_000, clock = clock) { it.rank(session, comparands(60), 5) }
        assertEquals(1_770, ranked.compared)
    }

    @Test
    fun `a quick bound that is the exact one is not computed again for a pair reached`() {
        val session = Drawn(seed = 4, quickIsExact = true)
        val ranked = ranking(2) { it.rankBounded(session, comparands(40), 3, partial = false) }
        assertEquals(0, session.bounds.get(), "bounded again ${session.bounds.get()} times")
        assertEquals(ids(ranking(2) { it.rank(Drawn(seed = 4), comparands(40), 3) }), ids(ranked))
    }

    @Test
    fun `a full run that keeps pace with its budget is not refused`() {
        val clock = ManualTicker()
        val ranked = ranking(1, budgetMs = 2_000, clock = clock) { it.rank(Drawn(seed = 3, scoreMs = 1, clock = clock), comparands(20), 5) }
        assertEquals(190, ranked.compared)
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
