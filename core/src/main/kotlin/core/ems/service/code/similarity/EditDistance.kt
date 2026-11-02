package core.ems.service.code.similarity

/** How much of two texts lines up character by character. Knows nothing about code, so it sees any text. */
internal object EditDistance {

    /**
     * fuzzywuzzy's `ratio`, as a share rather than a rounded percent:
     * 2·LCS / (|a| + |b|) over UTF-16 code units. Two empty texts are 0.
     *
     * Computed bit-parallel ([lcs]) rather than by fuzzywuzzy's full table: that is |a|·|b| cells, minutes
     * for a cohort; this is |a|·|b|/64 word steps.
     */
    fun ratio(a: String, b: String): Double {
        val total = a.length + b.length
        if (total == 0) return 0.0
        return (2 * lcs(a, b)) / total.toDouble()
    }

    /**
     * The length of the longest common subsequence, by Hyyrö's bit-parallel algorithm: the shorter text is
     * the pattern, one bit per character, and each character of the longer text advances the whole row at
     * once, 64 characters to a word.
     */
    fun lcs(a: String, b: String): Int {
        val pattern = if (a.length <= b.length) a else b
        val text = if (a.length <= b.length) b else a
        val m = pattern.length
        if (m == 0) return 0
        val words = (m + 63) ushr 6

        // Each distinct pattern character's positions as a bit mask; ASCII by index, the rest by map.
        val ascii = arrayOfNulls<LongArray>(128)
        val other = HashMap<Char, LongArray>()
        fun maskOf(c: Char): LongArray? = if (c.code < 128) ascii[c.code] else other[c]
        for (i in 0 until m) {
            val c = pattern[i]
            val mask = maskOf(c) ?: LongArray(words).also { if (c.code < 128) ascii[c.code] = it else other[c] = it }
            mask[i ushr 6] = mask[i ushr 6] or (1L shl (i and 63))
        }

        val row = LongArray(words) { -1L }
        for (c in text) {
            // A character the pattern does not contain leaves the row as it was.
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
}
