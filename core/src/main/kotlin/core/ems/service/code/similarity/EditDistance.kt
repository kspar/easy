package core.ems.service.code.similarity

/**
 * The two whole-text measures the similarity check used before JPlag, kept as [EditDistanceProvider]:
 * how much of two texts can be lined up character by character, and how many of their character pairs
 * they share. Neither knows anything about code, so neither sees through renaming; both see any text.
 */
internal object EditDistance {

    /**
     * fuzzywuzzy's `ratio` - `FuzzySearch.ratio` was the old check's "Levenshtein" score - as a share
     * rather than a rounded percent: 2·LCS / (|a| + |b|), the indel distance's complement, over UTF-16
     * code units as fuzzywuzzy counts them. Two empty texts are 0, which is what fuzzywuzzy's 0/0 rounds to.
     *
     * The same number, computed in [lcs]'s bit-parallel way rather than fuzzywuzzy's full table: that
     * table is |a|·|b| cells, a quarter of a second for two 20,000-character solutions and minutes for a
     * cohort; this is |a|·|b|/64 word steps. [poll] is called every [POLL_ROWS] characters of the longer
     * text, for the caller to stop a comparison that has outrun its budget.
     */
    fun ratio(a: String, b: String, poll: () -> Unit = {}): Double {
        val total = a.length + b.length
        if (total == 0) return 0.0
        return (2 * lcs(a, b, poll)) / total.toDouble()
    }

    /**
     * The length of the longest common subsequence of [a] and [b], by Hyyrö's bit-parallel algorithm:
     * the shorter text is the pattern, one bit per character, and each character of the longer text
     * advances the whole row at once, 64 characters to a word. A character the pattern does not contain
     * leaves the row as it was, so it costs nothing.
     */
    fun lcs(a: String, b: String, poll: () -> Unit = {}): Int {
        val pattern = if (a.length <= b.length) a else b
        val text = if (a.length <= b.length) b else a
        val m = pattern.length
        if (m == 0) return 0
        val words = (m + 63) ushr 6

        // Each distinct pattern character's positions, as a bit mask. Code is mostly ASCII, so those are
        // found by index, and only the rest through a map.
        val ascii = arrayOfNulls<LongArray>(128)
        val other = HashMap<Char, LongArray>()
        fun maskOf(c: Char): LongArray? = if (c.code < 128) ascii[c.code] else other[c]
        for (i in 0 until m) {
            val c = pattern[i]
            val mask = maskOf(c) ?: LongArray(words).also { if (c.code < 128) ascii[c.code] = it else other[c] = it }
            mask[i ushr 6] = mask[i ushr 6] or (1L shl (i and 63))
        }

        val row = LongArray(words) { -1L }
        for ((index, c) in text.withIndex()) {
            if (index % POLL_ROWS == POLL_ROWS - 1) poll()
            val mask = maskOf(c) ?: continue
            // row' = (row + u) | (row & ~u), where u = row & mask, with the carry run across the words.
            var carry = 0L
            for (w in 0 until words) {
                val v = row[w]
                val u = v and mask[w]
                val sum = v + u + carry
                carry = if (java.lang.Long.compareUnsigned(sum, v) < 0 || (carry != 0L && sum == v)) 1L else 0L
                row[w] = sum or (v and u.inv())
            }
        }
        // The zeros in the pattern's m bits are the matched characters.
        var ones = 0
        for (w in 0 until words) {
            val bits = if (w == words - 1 && m and 63 != 0) row[w] and ((1L shl (m and 63)) - 1) else row[w]
            ones += java.lang.Long.bitCount(bits)
        }
        return m - ones
    }

    /**
     * The Sørensen–Dice coefficient of two texts' sets of character bigrams: 2·|A∩B| / (|A| + |B|). A text
     * shorter than two characters is the set of itself, as it always was.
     *
     * The old check counted the intersection by shrinking the first set to it, and then divided by the
     * shrunken set's size - 2·|A∩B| / (|A∩B| + |B|), which is higher, and different depending on which of
     * the two came first. This is the coefficient itself, so a pair scores the same either way round and
     * the scores are lower than they were.
     */
    fun dice(a: LongArray, b: LongArray): Double {
        var i = 0
        var j = 0
        var common = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] < b[j] -> i++
                a[i] > b[j] -> j++
                else -> { common++; i++; j++ }
            }
        }
        return (2.0 * common) / (a.size + b.size)
    }

    /** [text]'s distinct bigrams, sorted, for [dice]: built once per solution rather than once per pair. */
    fun bigrams(text: String): LongArray = when (text.length) {
        0 -> longArrayOf(EMPTY)
        1 -> longArrayOf(SINGLE or text[0].code.toLong())
        else -> LongArray(text.length - 1) { (text[it].code.toLong() shl 16) or text[it + 1].code.toLong() }.also { it.sort() }.distinctSorted()
    }

    private fun LongArray.distinctSorted(): LongArray {
        if (isEmpty()) return this
        var n = 1
        for (i in 1 until size) if (this[i] != this[n - 1]) this[n++] = this[i]
        return copyOf(n)
    }

    /** Above any bigram, which takes 32 bits: a one-character text, and the empty one, are sets of their own. */
    private const val SINGLE = 1L shl 40
    private const val EMPTY = 1L shl 41

    const val POLL_ROWS = 4096
}
