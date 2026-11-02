package core.ems.service.code.similarity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.random.Random

class EditDistanceTest {

    /** The longest common subsequence the slow way, to hold the bit-parallel one to. */
    private fun lcsByTable(a: String, b: String): Int {
        val row = IntArray(b.length + 1)
        for (i in 1..a.length) {
            var diagonal = 0
            for (j in 1..b.length) {
                val above = row[j]
                row[j] = if (a[i - 1] == b[j - 1]) diagonal + 1 else maxOf(row[j], row[j - 1])
                diagonal = above
            }
        }
        return row[b.length]
    }

    @Test
    fun `the longest common subsequence is exact, across 64-character words and outside ASCII`() {
        val random = Random(1667)
        val alphabets = listOf(
            "abcdefgh ",
            "def f(x):\n    return x + 1\n()[]{}=<>;,.\"'",
            "õäöüšž ÕÄÖÜ abc",
            // A combining diaeresis and an emoji: two UTF-16 units each.
            "a" + Char(0x308) + "o" + String(Character.toChars(0x1F600)) + "x ",
        )
        val lengths = listOf(0, 1, 63, 64, 65, 128, 129, 1000)
        val texts = alphabets.flatMap { alphabet -> lengths.map { n -> String(CharArray(n) { alphabet[random.nextInt(alphabet.length)] }) } }
        for (a in texts) for (b in texts) assertEquals(lcsByTable(a, b), EditDistance.lcs(a, b), "${a.length} vs ${b.length}")
    }

    /**
     * fuzzywuzzy's `FuzzySearch.ratio` for each pair, recorded from fuzzywuzzy 1.4.0 - the score the check
     * showed before - so the library need not be a dependency to hold the new code to it. Each pins one of
     * what the subsequence alone does not: rounding, empty texts, and what a character is.
     */
    @Test
    fun `the ratio is the one similarity always reported`() {
        val emoji = String(Character.toChars(0x1F600))
        val recorded = listOf(
            Triple("", "", 0),                      // 0/0 is 0, not 100
            Triple("", "abc", 0),
            Triple("abc", "abc", 100),
            Triple("kitten", "sitting", 62),
            Triple("x", "x" + "y".repeat(14), 13),  // 12.5 rounds up
            Triple(emoji, emoji + "a", 80),         // UTF-16 units: 4/5, not 2/3
            Triple("a" + Char(0x308), Char(0xE4).toString(), 0), // no normalisation
            Triple("Abc", "abc", 67),               // no case folding
            Triple("a = 1", "a=1", 75),             // layout counts
            Triple("SELECT name FROM people WHERE age > 18;", "select name from people where age>18", 56),
        )
        for ((a, b, expected) in recorded) {
            assertEquals(expected, Math.round(100 * EditDistance.ratio(a, b)).toInt(), "'$a' vs '$b'")
            assertEquals(expected, Math.round(100 * EditDistance.ratio(b, a)).toInt(), "'$b' vs '$a'")
        }
    }
}
