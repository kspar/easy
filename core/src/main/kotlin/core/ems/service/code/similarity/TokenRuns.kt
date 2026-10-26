package core.ems.service.code.similarity

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * One submission's tokens as a bound on greedy string tiling reads them (EZ-1667): how many of its tokens a
 * tiling against another could possibly match, without tiling.
 *
 * A tile is a run of at least [k] tokens with the same types in both submissions, so every matched token
 * lies inside some window of [k] consecutive tokens whose types the other submission also has, in that
 * order. Counting the tokens inside such windows - on both sides, and no more than the two share by type
 * at all - bounds the matched tokens from above. It is a merge of two sorted lists of window hashes, so it
 * costs a few microseconds where the tiling costs tens of them, and tens where the tiling costs tens of
 * milliseconds; measured on the EZ-1667 stress cohorts, it left 2% of an ordinary cohort's pairs to tile.
 *
 * A window holding a token excluded from matching (a file's end) is no window: no tile can cross one.
 * Tokens the starter code marks are excluded from matching too, but only after this is built; counting
 * them here only raises the bound. Two windows with equal hashes but different types also only raise it.
 */
internal class TokenRuns private constructor(
    private val length: Int,
    private val k: Int,
    /** The types matching reads, sorted, and how many of each. */
    private val types: IntArray,
    private val counts: IntArray,
    /**
     * Every window, sorted: its hash in the high bits and where it starts in the low [POSITION_BITS], one
     * primitive each - a boxed pair of them was some 400 KB of garbage for each solution at the cap.
     */
    private val windows: LongArray,
) {
    /** At least as many tokens as a tiling of [this] against [other] matches, on either side. */
    fun matchedBound(other: TokenRuns): Int = minOf(sharedTypes(other), covered(other), other.covered(this))

    /** Tokens the two have of each type, the smaller count of each: matching never pairs more. */
    private fun sharedTypes(other: TokenRuns): Int {
        var shared = 0
        var i = 0
        var j = 0
        while (i < types.size && j < other.types.size) {
            when {
                types[i] < other.types[j] -> i++
                types[i] > other.types[j] -> j++
                else -> shared += minOf(counts[i++], other.counts[j++])
            }
        }
        return shared
    }

    /**
     * This submission's tokens inside a window [other] has too. Counted on a stamp array kept per thread and
     * marked with a new generation each call, so nothing is cleared or allocated: a fresh array a call was
     * some 2.5 GB of garbage over every pair of a cohort at the cap, inside the budget.
     */
    private fun covered(other: TokenRuns): Int {
        val marks = MARKS.get().next(length)
        var count = 0
        var i = 0
        var j = 0
        while (i < windows.size && j < other.windows.size) {
            // Signed, as the windows are sorted: an unsigned shift reorders the negative hashes after the rest,
            // the merge then passes matches by, and the bound falls below the tiling.
            val mine = windows[i] shr POSITION_BITS
            val theirs = other.windows[j] shr POSITION_BITS
            when {
                mine < theirs -> i++
                mine > theirs -> j++
                else -> {
                    val start = (windows[i] and POSITION_MASK).toInt()
                    for (position in start until start + k) if (marks.mark(position)) count++
                    // Not j++: the next window here may hash the same, and [other]'s window is still there.
                    i++
                }
            }
        }
        return count
    }

    /** One thread's stamps: a position is marked in this call when its stamp is this call's generation. */
    private class Marks {
        private var stamps = IntArray(0)
        private var generation = 0

        fun next(length: Int): Marks {
            if (stamps.size < length) stamps = IntArray(length)
            if (generation == Int.MAX_VALUE) {
                stamps.fill(0)
                generation = 0
            }
            generation++
            return this
        }

        /** Whether [position] was not yet marked in this call; it is now. */
        fun mark(position: Int): Boolean {
            if (stamps[position] == generation) return false
            stamps[position] = generation
            return true
        }
    }

    companion object {
        /** [typeIds] is the submission's token types in order, as small numbers, and -1 for one excluded from matching. */
        fun of(typeIds: IntArray, k: Int): TokenRuns {
            require(typeIds.size <= POSITION_MASK) { "${typeIds.size} tokens is more than a window's start can hold" }
            val counted = typeIds.filter { it >= 0 }.groupingBy { it }.eachCount().toSortedMap()
            val windows = LongArray(maxOf(0, typeIds.size - k + 1))
            var found = 0
            if (k > 0) for (start in 0..typeIds.size - k) {
                var hash = FNV_OFFSET
                var usable = true
                for (position in start until start + k) {
                    if (typeIds[position] < 0) {
                        usable = false
                        break
                    }
                    hash = (hash xor typeIds[position].toLong()) * FNV_PRIME
                }
                // The hash loses its low bits to the start: more windows hash alike, which only raises the bound.
                if (usable) windows[found++] = (hash ushr POSITION_BITS shl POSITION_BITS) or start.toLong()
            }
            val sorted = windows.copyOf(found).apply { sort() }
            return TokenRuns(typeIds.size, k, counted.keys.toIntArray(), counted.values.toIntArray(), sorted)
        }

        /** A submission's tokens are capped at thousands, so 24 bits hold any window's start. */
        private const val POSITION_BITS = 24
        private const val POSITION_MASK = (1L shl POSITION_BITS) - 1

        private const val FNV_OFFSET = -3750763034362895579L
        private const val FNV_PRIME = 1099511628211L

        private val MARKS: ThreadLocal<Marks> = ThreadLocal.withInitial { Marks() }
    }
}

/**
 * Keys as small numbers, each its own however many threads ask at once (EZ-1667): JPlag's token types for
 * [TokenRuns]. Numbered from the map's size, two types first seen on two threads at once could both take
 * the same number - a bound no lower, but looser and different run to run, and with it the order a run
 * stopped at its deadline had reached its pairs in.
 */
internal class Numbering<K : Any> {
    private val next = AtomicInteger()
    private val numbers = ConcurrentHashMap<K, Int>()

    fun of(key: K): Int = numbers.computeIfAbsent(key) { next.getAndIncrement() }
}
