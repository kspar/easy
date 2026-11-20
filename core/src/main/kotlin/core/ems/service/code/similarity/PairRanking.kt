package core.ems.service.code.similarity

import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * What a pair is compared as: one submission, or a group of identical ones compared once for all of them,
 * through its first. [members] are the ids, a group's in id order, and [owners] their authors.
 */
internal class Comparand(val members: List<Long>, val owners: List<String>) {
    val representative: Long get() = members.first()

    /** Whether some member of each has a different author from some member of the other. */
    fun apartFrom(other: Comparand): Boolean = owners.any { mine -> other.owners.any { it != mine } }
}

/** The kept pairs, and how many pairs scored a reportable share at all. */
internal class Ranking(val top: List<SimilarityPair>, val scoring: Int)

/**
 * Compares every pair worth comparing, on every worker, and keeps the top N by [BEST_FIRST] and the top N
 * by [HIGHEST_FIRST], merged and in [BEST_FIRST]'s order.
 *
 * Ranked by how much code a pair shares first: by the share alone, a twenty-token stub handed in unchanged
 * is 100% of itself inside every solution grown from it, and a dozen of them pushed a real copy out of the
 * page's list. The top N by the share are kept as well, so that the page's threshold, which filters on the
 * share, finds every pair above it that a list by share would hold. Pairs under half a percent - shown as
 * 0% - are dropped rather than ranked.
 */
internal class PairRanking(private val workers: SimilarityWorkers) {

    /** Pairs scored so far, of [total], for the teacher waiting on the run. */
    val done = AtomicLong()

    @Volatile
    var total = 0L
        private set

    fun rank(cohort: PreparedCohort, comparands: List<Comparand>, topN: Int): Ranking {
        total = comparands.indices.sumOf { i -> (i + 1 until comparands.size).count { j -> comparands[i].apartFrom(comparands[j]) }.toLong() }
        val stripes = workers.all((0 until workers.threads).map { stripe -> { stripe(stripe, cohort, comparands, topN) } })
        val byShared = stripes.flatMap { it.byShared }.sortedWith(BEST_FIRST).take(topN)
        val byScore = stripes.flatMap { it.byScore }.sortedWith(HIGHEST_FIRST).take(topN)
        val top = (byShared + byScore).distinct().sortedWith(BEST_FIRST)
        return Ranking(top, stripes.sumOf { it.scoring })
    }

    private class Stripe(val byShared: List<SimilarityPair>, val byScore: List<SimilarityPair>, val scoring: Int)

    /**
     * Rows [first], first + threads, … of the pair triangle, so each worker gets a similar share of long and
     * short rows. Its own top N by each order is enough: every pair of a global top N is in its stripe's.
     */
    private fun stripe(first: Int, cohort: PreparedCohort, comparands: List<Comparand>, topN: Int): Stripe {
        val byShared = PriorityQueue(BEST_FIRST.reversed())
        val byScore = PriorityQueue(HIGHEST_FIRST.reversed())
        var scoring = 0
        for (i in first until comparands.size step workers.threads) {
            for (j in i + 1 until comparands.size) {
                if (!comparands[i].apartFrom(comparands[j])) continue
                // Another worker failed, so this stripe's answer will be thrown away.
                if (workers.stopped) return Stripe(emptyList(), emptyList(), scoring)
                workers.checkDeadline()
                val pair = cohort.score(comparands[i].representative, comparands[j].representative)
                done.incrementAndGet()
                if (pair.maxSimilarity < MIN_REPORTED) continue
                scoring++
                byShared += pair
                if (byShared.size > topN) byShared.poll()
                byScore += pair
                if (byScore.size > topN) byScore.poll()
            }
        }
        return Stripe(byShared.toList(), byScore.toList(), scoring)
    }

    companion object {
        /** The smallest score the page shows as 1% rather than 0%. */
        private const val MIN_REPORTED = 0.005

        /**
         * Most shared first, then highest score, then similarity, then by id. Total, so which of two equal
         * pairs makes the top N does not depend on how the pairs were split across workers.
         */
        internal val BEST_FIRST: Comparator<SimilarityPair> = compareByDescending<SimilarityPair> { it.sharedTokens ?: 0 }
            .thenByDescending { it.maxSimilarity }
            .thenByDescending { it.similarity }
            .thenBy { minOf(it.id1, it.id2) }
            .thenBy { maxOf(it.id1, it.id2) }

        /** Highest score first, then similarity, what is shared and ids: the order the page's threshold filters in. */
        internal val HIGHEST_FIRST: Comparator<SimilarityPair> = compareByDescending<SimilarityPair> { it.maxSimilarity }
            .thenByDescending { it.similarity }
            .thenByDescending { it.sharedTokens ?: 0 }
            .thenBy { minOf(it.id1, it.id2) }
            .thenBy { maxOf(it.id1, it.id2) }
    }
}
