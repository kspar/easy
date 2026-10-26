package core.ems.service.code.similarity

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
    /** Every window's hash, sorted, and where in the submission each starts. */
    private val windows: LongArray,
    private val starts: IntArray,
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

    /** This submission's tokens inside a window [other] has too. */
    private fun covered(other: TokenRuns): Int {
        val inside = BooleanArray(length)
        var i = 0
        var j = 0
        while (i < windows.size && j < other.windows.size) {
            when {
                windows[i] < other.windows[j] -> i++
                windows[i] > other.windows[j] -> j++
                else -> {
                    // Not j++: the next window here may hash the same, and [other]'s window is still there.
                    for (position in starts[i] until starts[i] + k) inside[position] = true
                    i++
                }
            }
        }
        return inside.count { it }
    }

    companion object {
        /** [typeIds] is the submission's token types in order, as small numbers, and -1 for one excluded from matching. */
        fun of(typeIds: IntArray, k: Int): TokenRuns {
            val counted = typeIds.filter { it >= 0 }.groupingBy { it }.eachCount().toSortedMap()
            val hashes = ArrayList<Pair<Long, Int>>()
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
                if (usable) hashes += hash to start
            }
            hashes.sortBy { it.first }
            return TokenRuns(
                typeIds.size, k,
                counted.keys.toIntArray(), counted.values.toIntArray(),
                LongArray(hashes.size) { hashes[it].first }, IntArray(hashes.size) { hashes[it].second },
            )
        }

        private const val FNV_OFFSET = -3750763034362895579L
        private const val FNV_PRIME = 1099511628211L
    }
}
