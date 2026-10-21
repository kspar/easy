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
     * A text reduced to what [lcsBound] needs: its length, its characters sorted, and its bigrams sorted,
     * every occurrence of each kept - multisets, unlike [bigrams]'s set. Built once per solution.
     */
    class Profile(val length: Int, val chars: CharArray, val bigrams: IntArray)

    fun profile(text: String): Profile = Profile(
        text.length,
        text.toCharArray().also { it.sort() },
        IntArray(maxOf(text.length - 1, 0)) { (text[it].code shl 16) or text[it + 1].code }.also { it.sort() },
    )

    /**
     * An upper bound on [lcs] of the two texts, from their profiles alone - linear in their lengths, where
     * [lcs] is their product over 64. What lets the optimised comparison rule a pair out without scoring
     * it, so it must never be below the real value; it is the smallest of three that each hold:
     *
     *  - the shorter length: a common subsequence is a subsequence of both;
     *  - the characters the two have in common, counted with repeats: every character of a common
     *    subsequence is one of each text's;
     *  - the q-gram lemma, for q = 2. Deleting or inserting one character changes at most three bigrams -
     *    those it is part of, and the one across the gap - so the d insertions and deletions that turn one
     *    text into the other change the bigram multiset by at most 3d, and d is at least a third of the
     *    multisets' symmetric difference. The indel distance d is |a| + |b| - 2·LCS, so LCS is at most
     *    (|a| + |b| - ⌈Δ/3⌉) / 2. This is what tells apart two texts made of the same characters in
     *    another order, which the second barely can.
     *
     * Trigrams were tried as well and ruled out not one more pair on generated cohorts, at eight bytes a
     * character. How much any of this rules out is measured, and modest: unrelated solutions in one
     * language are 60-70% alike character by character, near the N-th best pair, so on a cohort of like
     * lengths almost nothing is below the floor. What the bounds buy everywhere is the order - likeliest
     * first - which is what makes a run stopped at its budget return the best pairs rather than any.
     */
    fun lcsBound(a: Profile, b: Profile): Int {
        val shared = common(a.chars, b.chars)
        val byBigrams = qGramBound(a.length + b.length, a.bigrams.size + b.bigrams.size - 2 * common(a.bigrams, b.bigrams), 2)
        return minOf(minOf(a.length, b.length), shared, byBigrams)
    }

    /** The lemma's bound on the LCS, from the two texts' total length and their q-gram multisets' symmetric [difference]. */
    private fun qGramBound(total: Int, difference: Int, q: Int): Int {
        val perEdit = 2 * q - 1
        val minEdits = (difference + perEdit - 1) / perEdit
        return (total - minEdits) / 2
    }

    /** [ratio]'s upper bound from [lcsBound], computed the same way, so that it is never below it in floating point either. */
    fun ratioBound(a: Profile, b: Profile): Double {
        val total = a.length + b.length
        if (total == 0) return 0.0
        return (2 * lcsBound(a, b)) / total.toDouble()
    }

    /** How many elements two sorted multisets share, counting repeats. */
    private fun common(a: CharArray, b: CharArray): Int {
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
        return common
    }

    private fun common(a: IntArray, b: IntArray): Int {
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
        return common
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
