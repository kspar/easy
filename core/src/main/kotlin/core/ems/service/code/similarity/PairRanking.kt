package core.ems.service.code.similarity

import de.jplag.JPlagComparison
import de.jplag.Submission
import de.jplag.SubmissionSet
import de.jplag.comparison.GreedyStringTiling
import de.jplag.comparison.TokenSequenceMapper
import de.jplag.options.JPlagOptions
import java.util.PriorityQueue

/**
 * What one pair is compared as: a submission, or a group of identical ones compared once (see
 * `SimilarityService.comparands`). [submission] is the one compared for them all; [members] are their
 * ids and [owners] their authors, a group's in id order, the first of them [submission]'s.
 */
internal class Comparand(val submission: Submission, val members: List<Long>, val owners: List<String?>) {
    /**
     * Whether this and [other] are worth comparing: whether some member of each has a different author
     * from some member of the other. A student's own attempts are never reported against each other (see
     * SimilaritySource.owner); an unknown author, null, differs from everyone.
     */
    fun apartFrom(other: Comparand): Boolean = owners.any { mine -> other.owners.any { theirs -> mine == null || theirs == null || mine != theirs } }
}

/** One compared pair, with the sort keys computed once: each streams over the match list per call. */
internal class Ranked(
    val comparison: JPlagComparison,
    val maximum: Double,
    val average: Double,
    /** Matched tokens: how much code the two share, rather than what share of either it is. */
    val shared: Int,
    val lowId: Long,
    val highId: Long,
)

internal class Ranking(val top: List<Ranked>, val scoring: Int, val compared: Int)

/**
 * Compares every pair of a cohort that is worth comparing, and keeps the best: the top N by
 * [BEST_FIRST] and the top N by [HIGHEST_FIRST], together, in [BEST_FIRST]'s order.
 *
 * JPlag's algorithm, not its driver. `LongestCommonSubsequenceSearch` submits every pair up front as a
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
 * Ranked by how much code a pair shares - matched tokens - and only then by the higher of the two
 * percentages. By the percentage alone, the size of what is shared did not count: a twenty-token stub,
 * the starter code handed in unchanged, is 100% of itself inside every solution that grew from it, so
 * identical stubs outranked every real copy that was not also 100% on both scores. Twelve of them among
 * twenty students pushed a copy from first to 67th, out of the page's top N entirely on a larger class.
 * By what is shared the copy was first again. It keeps the case the higher percentage was chosen for: a
 * short solution pasted whole into a long one shares all of itself, and a real solution is not small.
 * maximalSimilarity is max(similarityOfFirst, similarityOfSecond) and similarity their mediant, so the
 * maximum is always the larger of the two - a maxOf() here would read as even-handed and never pick the
 * average.
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
    private val compare: (GreedyStringTiling, Submission, Submission) -> JPlagComparison,
) {

    /** [comparands] are the set's submissions, identical ones as one; see [Comparand]. */
    fun rank(set: SubmissionSet, options: JPlagOptions, comparands: List<Comparand>, topN: Int): Ranking {
        val gst = GreedyStringTiling(options, TokenSequenceMapper(set))
        if (set.hasBaseCode()) {
            // One submission at a time, and before any pair: JPlag documents generateBaseCodeMarking as
            // not thread-safe, and every comparison reads the markings it leaves. Each is a tiling of its
            // own, against the template, so the deadline is read before each as it is before each pair.
            comparands.forEach {
                if (deadline.passed()) throw SimilarityTimedOut(budgetSec)
                it.submission.baseCodeComparison = gst.generateBaseCodeMarking(it.submission, set.baseCode)
            }
        }
        val stripes = workers.all(
            (0 until workers.threads).map { stripe -> { stripe(stripe, gst, comparands, topN) } },
        )
        val byShared = stripes.flatMap { it.byShared }.sortedWith(BEST_FIRST).take(topN)
        val byPercent = stripes.flatMap { it.byPercent }.sortedWith(HIGHEST_FIRST).take(topN)
        // A pair in both is one object in both, and Ranked compares by identity.
        val top = (byShared + byPercent).distinct().sortedWith(BEST_FIRST)
        return Ranking(top, stripes.sumOf { it.scoring }, stripes.sumOf { it.compared })
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
    private fun stripe(first: Int, gst: GreedyStringTiling, comparands: List<Comparand>, topN: Int): Stripe {
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
                val comparison = compare(gst, comparands[i].submission, comparands[j].submission)
                compared++
                val maximum = comparison.maximalSimilarity()
                if (maximum < MIN_REPORTED_SIMILARITY) continue
                scoring++
                val a = comparands[i].members.first()
                val b = comparands[j].members.first()
                val ranked = Ranked(comparison, maximum, comparison.similarity(), comparison.matches().sumOf { it.minimumLength() }, minOf(a, b), maxOf(a, b))
                byShared += ranked
                if (byShared.size > topN) byShared.poll()
                byPercent += ranked
                if (byPercent.size > topN) byPercent.poll()
            }
        }
        return Stripe(byShared.toList(), byPercent.toList(), compared, scoring)
    }

    companion object {
        /** The smallest maximum the page shows as 1% rather than 0%: it rounds to whole percents. */
        private const val MIN_REPORTED_SIMILARITY = 0.005

        /**
         * The ranking the page shows: most code shared first, then highest maximum, then highest average,
         * then by id. Total on purpose - pairs are split across workers, and with ties left open that split
         * would decide which of two equal pairs made the top N, so the answer would depend on the thread
         * count.
         */
        internal val BEST_FIRST: Comparator<Ranked> = compareByDescending<Ranked> { it.shared }
            .thenByDescending { it.maximum }
            .thenByDescending { it.average }
            .thenBy { it.lowId }
            .thenBy { it.highId }

        /** The order the page's threshold filters in: highest maximum first, then average, shared tokens and ids. Total too. */
        internal val HIGHEST_FIRST: Comparator<Ranked> = compareByDescending<Ranked> { it.maximum }
            .thenByDescending { it.average }
            .thenByDescending { it.shared }
            .thenBy { it.lowId }
            .thenBy { it.highId }
    }
}
