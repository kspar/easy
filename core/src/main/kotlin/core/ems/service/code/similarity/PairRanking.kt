package core.ems.service.code.similarity

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * What one pair is compared as: a submission, or a group of identical ones compared once (see
 * `SimilarityService.comparands`). [representative] is the one compared for them all; [members] are
 * their ids and [owners] their authors, a group's in id order, the first of them [representative].
 */
internal class Comparand(val representative: Long, val members: List<Long>, val owners: List<String?>) {
    /**
     * Whether this and [other] are worth comparing: whether some member of each has a different author
     * from some member of the other. A student's own attempts are never reported against each other (see
     * SimilaritySource.owner); an unknown author, null, differs from everyone.
     */
    fun apartFrom(other: Comparand): Boolean = owners.any { mine -> other.owners.any { theirs -> mine == null || theirs == null || mine != theirs } }
}

/** One compared pair, and the ids its order ends on: the lower and higher of its two representatives. */
internal class Ranked(val pair: PairScore, val lowId: Long, val highId: Long)

/**
 * The kept pairs, with the counts behind them: [scoring] of the [compared] pairs scored a reportable
 * percentage, out of [total] worth comparing. [complete] is false when the run stopped at its deadline
 * with pairs left; [mayHaveMore] is true when pairs left uncompared - ruled out, or never reached - could
 * still have scored, so more scoring pairs may exist than [scoring] says.
 */
internal class Ranking(
    val top: List<Ranked>,
    val scoring: Int,
    val compared: Int,
    val total: Int = compared,
    val complete: Boolean = true,
    val mayHaveMore: Boolean = false,
)

/**
 * Compares every pair of a cohort that is worth comparing, and keeps the best: the top N by
 * [BEST_FIRST] and the top N by [HIGHEST_FIRST], together, in [BEST_FIRST]'s order.
 *
 * The provider's measure, not its driver. JPlag's `LongestCommonSubsequenceSearch` submits every pair up front as a
 * virtual thread - 124,750 at max-submissions: 500, on all cores - keeps every comparison however low,
 * and sorts them all by average similarity, recomputing it from the match list on both sides of every
 * step of the sort. Over the same 124,750 pairs of generated solutions that took 6-9 s on eight cores,
 * and this 1.5-2.1 s on four, with the same results. Being ours, it is also the only place that can:
 *
 *  - read the deadline between pairs. JPlag's driver waits up to 24 hours;
 *  - leave a student's own pairs uncompared, rather than compare them and throw them away (see
 *    SimilaritySource.owner for why they are never reported);
 *  - keep only the best per worker instead of every comparison ever made.
 *
 * Ranked by how much code a pair shares - [PairScore.rank]; for JPlag, matched tokens - and only then by
 * the score, the higher of the two percentages. By the percentage alone, the size of what is shared did not count: a twenty-token stub,
 * the starter code handed in unchanged, is 100% of itself inside every solution that grew from it, so
 * identical stubs outranked every real copy that was not also 100% on both scores. Twelve of them among
 * twenty students pushed a copy from first to 67th, out of the page's top N entirely on a larger class.
 * By what is shared the copy was first again. It keeps the case the higher percentage was chosen for: a
 * short solution pasted whole into a long one shares all of itself, and a real solution is not small.
 *
 * Kept by that order alone, though, the cut and the page's threshold would disagree: the threshold
 * filters on the percentage, and a short copy at 95% sharing few tokens could fall below a hundred long
 * honest pairs at 45% and be cut - so a teacher looking at everything above 90% saw nothing, with a notice
 * that said the highest-scoring pairs were there. So the top N by the percentage are kept as well, and
 * the two sets merged: at most 2N pairs, ordered by what is shared, whose part above any threshold is
 * exactly what the percentage alone would have kept above it, as long as fewer than N are.
 *
 * Pairs that would read as 0% are dropped, not merely left unranked. JPlag's default threshold is 0 and
 * keeps every pair; handing a teacher a hundred 0% rows makes the list read as a hundred findings and
 * trains them to ignore it. The cut is half a percent rather than zero because the page shows whole
 * percents: one six-token run between two 1,500-token essays scores 0.4%, displays as 0%, and would still
 * count toward `truncated`.
 */
internal class PairRanking(
    private val workers: SimilarityWorkers,
    private val deadline: Deadline,
    private val budgetSec: Long,
    /** Whether [rank] refuses a run its own pace says cannot finish; see [Pace]. Off only in a test of the deadline itself. */
    private val project: Boolean = true,
) {
    private val log = KotlinLogging.logger {}

    /** [comparands] are the cohort's usable submissions, identical ones as one; see [Comparand]. */
    fun rank(session: ProviderSession, comparands: List<Comparand>, topN: Int): Ranking {
        val pace = if (project) Pace(comparands) else null
        val stripes = workers.all(
            (0 until workers.threads).map { stripe -> { stripe(stripe, session, comparands, topN, pace) } },
        )
        val byShared = stripes.flatMap { it.byShared }.sortedWith(BEST_FIRST).take(topN)
        val byPercent = stripes.flatMap { it.byPercent }.sortedWith(HIGHEST_FIRST).take(topN)
        // A pair in both is one object in both, and Ranked compares by identity.
        val top = (byShared + byPercent).distinct().sortedWith(BEST_FIRST)
        return Ranking(top, stripes.sumOf { it.scoring }, stripes.sumOf { it.compared })
    }

    /**
     * The optimised mode (EZ-1667): the same two top N as [rank], from the pairs [ProviderSession.bound]
     * cannot rule out, scored likeliest first.
     *
     * Every pair worth comparing is ordered by its [ProviderSession.quickBound], highest first - by what the
     * pair could share, then what it could score, since the score's bound is about sizes more than texts and
     * near 1 for every pair of like length - and taken in
     * batches across the workers, each of which bounds its pairs exactly ([ProviderSession.bound]) before
     * scoring them. Once N pairs are kept in an order, its N-th is a floor: a pair whose bound is below the
     * floor in both orders cannot enter either top N, then or later, since floors only rise, and is not
     * scored. No bound is below the real value, so the pairs kept are exactly the pairs [rank] keeps.
     *
     * With [partial], the deadline ends the run with what it has, flagged incomplete, instead of refusing
     * it: the likeliest pairs having gone first, what is kept by then is the best the budget could find.
     */
    fun rankBounded(session: ProviderSession, comparands: List<Comparand>, topN: Int, partial: Boolean): Ranking {
        // Ordered by the quick bounds, which cost little; the exact ones are computed by the workers, pair by
        // pair, just before scoring - in parallel, and stopped by the deadline like the scoring is.
        val stripes = workers.all((0 until workers.threads).map { stripe -> { bounded(stripe, session, comparands) } })
        val total = stripes.sumOf { it.pairs }
        val candidates = stripes.flatMap { it.candidates }.sortedWith(
            // Total, so the order - and with it which pairs a cut-short run reached - is the same on any number of threads.
            compareByDescending<Candidate> { it.bound.rank }.thenByDescending { it.bound.score }
                .thenBy { minOf(it.a, it.b) }.thenBy { maxOf(it.a, it.b) },
        )

        val byShared = PriorityQueue(BEST_FIRST.reversed())
        val byPercent = PriorityQueue(HIGHEST_FIRST.reversed())
        var compared = 0
        var scoring = 0
        var mayHaveMore = false
        // Stopped while ordering: no pair is scored, and the run says it did not finish.
        var complete = candidates.size == total
        if (!complete && !partial) throw SimilarityTimedOut(budgetSec)
        var next = 0
        val batchSize = workers.threads * BATCH_PER_THREAD

        while (complete && next < candidates.size) {
            if (deadline.passed()) {
                if (!partial) throw SimilarityTimedOut(budgetSec)
                complete = false
                break
            }
            // The floors as they stand: a pair below both cannot be kept by either order.
            val floors = Floors(
                rank = if (byShared.size >= topN) byShared.peek().pair.rank else Double.NEGATIVE_INFINITY,
                score = if (byPercent.size >= topN) byPercent.peek().pair.score else Double.NEGATIVE_INFINITY,
            )
            val batch = mutableListOf<Candidate>()
            while (next < candidates.size && batch.size < batchSize) {
                val candidate = candidates[next++]
                when {
                    candidate.bound.score < MIN_REPORTED_SIMILARITY -> Unit
                    floors.rulesOut(candidate.bound) -> mayHaveMore = true
                    else -> batch += candidate
                }
            }
            if (batch.isEmpty()) continue
            val chunks = batch.chunked((batch.size + workers.threads - 1) / workers.threads)
            val worked = workers.all(chunks.map { chunk -> { scoreUntilDeadline(session, chunk, floors, partial) } })
            // A worker that stopped at the deadline keeps the pairs it finished; the rest of its chunk is lost.
            if (worked.zip(chunks).any { (done, chunk) -> done.size < chunk.size }) complete = false
            for (result in worked.flatten()) {
                if (result.ruledOut) {
                    mayHaveMore = true
                    continue
                }
                compared++
                val pair = result.pair
                if (pair == null || pair.score < MIN_REPORTED_SIMILARITY) continue
                scoring++
                val ranked = Ranked(pair, minOf(result.candidate.a, result.candidate.b), maxOf(result.candidate.a, result.candidate.b))
                byShared += ranked
                if (byShared.size > topN) byShared.poll()
                byPercent += ranked
                if (byPercent.size > topN) byPercent.poll()
            }
        }
        // A run stopped short may have missed anything, and `truncated` is all a page older than `complete`
        // can be told; the current page shows its own warning for `complete` instead of the cap notice.
        if (!complete) mayHaveMore = true

        val top = (byShared.toList() + byPercent.toList()).distinct().sortedWith(BEST_FIRST)
        return Ranking(top, scoring, compared, total, complete, mayHaveMore)
    }

    /** The N-th kept in each order: a pair bounded below both cannot be kept by either, then or later. */
    private class Floors(val rank: Double, val score: Double) {
        fun rulesOut(bound: PairBound): Boolean = bound.rank < rank && bound.score < score
    }

    /** A pair a worker reached: ruled out by its exact bound, or scored. */
    private class Worked(val candidate: Candidate, val pair: PairScore?, val ruledOut: Boolean)

    /**
     * [chunk]'s pairs in order: each bounded exactly, and scored unless that rules it out against [floors] -
     * the floors at the batch's start, which only rise, so a pair ruled out by them is out for good. With
     * [partial], it stops at the deadline - before a pair, or in the middle of one, which the provider ends
     * by throwing - and returns those it finished: thrown, the timeout lost the whole batch, and on a cold
     * JVM, whose first pairs are slow, that was every pair.
     */
    private fun scoreUntilDeadline(session: ProviderSession, chunk: List<Candidate>, floors: Floors, partial: Boolean): List<Worked> {
        val done = mutableListOf<Worked>()
        for (candidate in chunk) {
            if (deadline.passed()) {
                if (partial) break
                throw SimilarityTimedOut(budgetSec)
            }
            val exact = session.bound(candidate.a, candidate.b) ?: candidate.bound
            if (exact.score < MIN_REPORTED_SIMILARITY || floors.rulesOut(exact)) {
                done += Worked(candidate, null, ruledOut = exact.score >= MIN_REPORTED_SIMILARITY)
                continue
            }
            val pair = try {
                session.score(candidate.a, candidate.b)
            } catch (e: SimilarityTimedOut) {
                if (partial) break
                throw e
            }
            done += Worked(candidate, pair, ruledOut = false)
        }
        return done
    }

    /** A pair worth comparing, and how high it could score, by [ProviderSession.quickBound]. */
    private class Candidate(val a: Long, val b: Long, val bound: PairBound)

    /** One worker's share of the pairs, quickly bounded, and how many pairs it had: past the deadline, more than it bounded. */
    private class Bounded(val candidates: List<Candidate>, val pairs: Int)

    /**
     * Rows [first], first + threads, … of the pair triangle, quickly bounded, for [rankBounded]. Past the
     * deadline the rest are counted but not bounded: a quick bound is cheap, not free, and at the cap all of
     * them together are not.
     */
    private fun bounded(first: Int, session: ProviderSession, comparands: List<Comparand>): Bounded {
        val mine = mutableListOf<Candidate>()
        var pairs = 0
        for (i in first until comparands.size step workers.threads) {
            for (j in i + 1 until comparands.size) {
                if (!comparands[i].apartFrom(comparands[j])) continue
                if (workers.stopped) return Bounded(emptyList(), 0)
                pairs++
                if (deadline.passed()) continue
                val a = comparands[i].representative
                val b = comparands[j].representative
                mine += Candidate(a, b, checkNotNull(session.quickBound(a, b)) { "A bounded provider gave no bound" })
            }
        }
        return Bounded(mine, pairs)
    }

    /** One worker's share of the pairs, with its own counts: the workers share nothing but the stop. */
    private class Stripe(val byShared: List<Ranked>, val byPercent: List<Ranked>, val compared: Int, val scoring: Int)

    /**
     * Rows [first], first + threads, … of the pair triangle. Row i holds n-1-i pairs, so interleaving
     * rows gives each worker a similar share of long and short ones.
     *
     * Keeps its own [topN] by each order, which is enough: every pair of either global top N is in that
     * order's top N of the stripe it was compared in.
     */
    private fun stripe(first: Int, session: ProviderSession, comparands: List<Comparand>, topN: Int, pace: Pace?): Stripe {
        val byShared = PriorityQueue(BEST_FIRST.reversed())
        val byPercent = PriorityQueue(HIGHEST_FIRST.reversed())
        var compared = 0
        var scoring = 0
        for (i in first until comparands.size step workers.threads) {
            for (j in i + 1 until comparands.size) {
                if (!comparands[i].apartFrom(comparands[j])) continue
                // Another worker failed: this stripe's answer will be thrown away, so stop making it.
                if (workers.stopped) return Stripe(emptyList(), emptyList(), compared, scoring)
                if (deadline.passed()) throw SimilarityTimedOut(budgetSec)
                val a = comparands[i].representative
                val b = comparands[j].representative
                val pair = session.score(a, b)
                compared++
                pace?.pairDone()
                if (pair == null || pair.score < MIN_REPORTED_SIMILARITY) continue
                scoring++
                val ranked = Ranked(pair, minOf(a, b), maxOf(a, b))
                byShared += ranked
                if (byShared.size > topN) byShared.poll()
                byPercent += ranked
                if (byPercent.size > topN) byPercent.poll()
            }
        }
        return Stripe(byShared.toList(), byPercent.toList(), compared, scoring)
    }

    /**
     * A full run's pace, measured once a tenth of the time it has left is spent: the pairs done so far say
     * how long all of them will take, and a run that cannot finish is refused then, as too costly, rather
     * than holding the one permit to the deadline and timing out. The estimate prices a run before it
     * starts, from sizes and a cost model measured on one machine; this measures the run itself, on this
     * one. The stress test of EZ-1667 found the model under by a hundred times for JPlag on large
     * solutions, and by three for edit distance on a slower server: each admitted runs that then timed out.
     *
     * Only for runs that would be refused anyway - [rankBounded] stops at the deadline with what it has.
     * Checked once, and only past [PROJECTION_MARGIN] times the time left, since a cold JVM's first pairs
     * are slower than the rest and a run near the line may still finish.
     */
    private inner class Pace(comparands: List<Comparand>) {
        private val started = System.nanoTime()
        private val left = deadline.remainingNanos()
        private val checkAfter = left / PROJECT_AFTER_SHARE
        private val submissions = comparands.size
        private val total = comparands.indices.sumOf { i -> (i + 1 until comparands.size).count { j -> comparands[i].apartFrom(comparands[j]) }.toLong() }
        private val done = AtomicLong()
        private val checked = AtomicBoolean()

        fun pairDone() {
            val n = done.incrementAndGet()
            if (checked.get()) return
            val elapsed = System.nanoTime() - started
            if (elapsed < checkAfter || !checked.compareAndSet(false, true)) return
            val projected = elapsed * (total.toDouble() / n)
            if (projected <= left * PROJECTION_MARGIN) return
            val spentBefore = budgetSec * 1_000_000_000L - left
            val estimate = SimilarityEstimate(submissions, total, workers.threads, spentBefore + projected.toLong(), budgetSec)
            log.info { "Refusing a comparison at its pace: $n of $total pairs in ${elapsed / 1_000_000}ms, so about ${estimate.seconds}s in all, over the ${budgetSec}s budget" }
            throw SimilarityTooCostly(estimate)
        }
    }

    companion object {
        /** [Pace] is read once this share of the time left is spent: a tenth. */
        private const val PROJECT_AFTER_SHARE = 10

        /** How far past the time left [Pace] must project before it refuses. */
        private const val PROJECTION_MARGIN = 1.5

        /** The smallest score the page shows as 1% rather than 0%: it rounds to whole percents. */
        private const val MIN_REPORTED_SIMILARITY = 0.005

        /**
         * Pairs per worker in one batch of [rankBounded]. The floors are read between batches, so a smaller
         * batch rules out more pairs and a larger one waits less on the slowest worker; 32 keeps the wait
         * to a few pairs while the floors are seldom more than a batch out of date.
         */
        private const val BATCH_PER_THREAD = 32

        /**
         * The ranking the page shows: most shared first, then highest score, then highest similarity,
         * then by id. Total on purpose - pairs are split across workers, and with ties left open that split
         * would decide which of two equal pairs made the top N, so the answer would depend on the thread
         * count.
         */
        internal val BEST_FIRST: Comparator<Ranked> = compareByDescending<Ranked> { it.pair.rank }
            .thenByDescending { it.pair.score }
            .thenByDescending { it.pair.similarity }
            .thenBy { it.lowId }
            .thenBy { it.highId }

        /** The order the page's threshold filters in: highest score first, then similarity, what is shared and ids. Total too. */
        internal val HIGHEST_FIRST: Comparator<Ranked> = compareByDescending<Ranked> { it.pair.score }
            .thenByDescending { it.pair.similarity }
            .thenByDescending { it.pair.rank }
            .thenBy { it.lowId }
            .thenBy { it.highId }
    }
}
