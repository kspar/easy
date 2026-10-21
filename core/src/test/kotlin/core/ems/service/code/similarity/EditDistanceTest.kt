package core.ems.service.code.similarity

import me.xdrop.fuzzywuzzy.FuzzySearch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class EditDistanceTest {

    /** Texts the old check met, and the edges a bit-parallel row has: word boundaries, non-ASCII, nothing. */
    private val texts: List<String> = run {
        val random = Random(1667)
        val alphabets = listOf(
            "abcdefgh ",
            "def f(x):\n    return x + 1\n()[]{}=<>;,.\"'",
            "õäöüšž ÕÄÖÜ abc",
            // A letter with a combining diaeresis, and an emoji: two UTF-16 units each.
            "a" + Char(0x308) + "o" + "😀" + "x ",
        )
        val lengths = listOf(0, 1, 2, 63, 64, 65, 127, 128, 129, 200, 1000)
        alphabets.flatMap { alphabet ->
            lengths.map { n -> String(CharArray(n) { alphabet[random.nextInt(alphabet.length)] }) }
        }
    }

    /** The longest common subsequence the slow way, to hold the fast one to. */
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
    fun `the ratio is fuzzywuzzy's, as the old check reported it`() {
        for (a in texts) for (b in texts) {
            val expected = FuzzySearch.ratio(a, b)
            assertEquals(expected, Math.round(100 * EditDistance.ratio(a, b)).toInt(), "'${a.take(20)}…' (${a.length}) vs '${b.take(20)}…' (${b.length})")
        }
    }

    @Test
    fun `the longest common subsequence is exact, across word boundaries and outside ASCII`() {
        for (a in texts) for (b in texts) {
            assertEquals(lcsByTable(a, b), EditDistance.lcs(a, b), "${a.length} vs ${b.length}")
        }
    }

    @Test
    fun `dice is the coefficient itself, the same either way round`() {
        // The old check shrank the first set to the intersection and divided by its new size, so this
        // pair scored 100 one way round and 67 the other.
        val dice = { a: String, b: String -> EditDistance.dice(EditDistance.bigrams(a), EditDistance.bigrams(b)) }
        assertEquals(0.5, dice("abcd", "ab"), 1e-12)
        assertEquals(0.5, dice("ab", "abcd"), 1e-12)
        assertEquals(1.0, dice("abab", "baba"), 1e-12)
        assertEquals(1.0, dice("x", "x"), 1e-12)
        assertEquals(0.0, dice("x", "xy"), 1e-12)
        for (a in texts) for (b in texts) assertEquals(dice(a, b), dice(b, a), "${a.length} vs ${b.length}")
    }

    @Test
    fun `a long comparison can be stopped part way, where the budget runs out`() {
        val long = "ab".repeat(EditDistance.POLL_ROWS)
        assertThrows<SimilarityTimedOut> { EditDistance.ratio(long, long) { throw SimilarityTimedOut(1) } }
    }

    @Test
    fun `a solution above the character cap is skipped as too large, and a blank one as too small`() {
        val provider = EditDistanceProvider(maxSolutionChars = 10)
        SimilarityWorkers(1, 1L shl 20).use { workers ->
            val run = RunContext(workers, Deadline.after(TimeUnit.SECONDS.toNanos(60)), 60)
            val sources = listOf(SimilaritySource(1, "print(1)"), SimilaritySource(2, "x".repeat(11)), SimilaritySource(3, "  \n"), SimilaritySource(4, "print(2)"))
            provider.open(ProviderRequest(SimilarityLanguage.PYTHON, sources, null), run).use { session ->
                val cohort = session.prepare()
                assertEquals(listOf(1L, 4L), cohort.usable)
                assertEquals(listOf(SkippedSubmission(2, "TOO_LARGE"), SkippedSubmission(3, "TOO_SMALL")), cohort.skipped)
                val pair = session.score(4, 1)!!
                assertEquals(1L, pair.first)
                assertTrue(pair.score >= pair.similarity)
            }
        }
    }
}

class EditDistanceProviderContractTest : SimilarityProviderContractTest() {
    override fun provider(): SimilarityProvider = EditDistanceProvider()

    override val language = SimilarityLanguage.PYTHON

    override val original = """
        def count_words(text):
            words = text.split()
            result = {}
            for word in words:
                if word in result:
                    result[word] = result[word] + 1
                else:
                    result[word] = 1
            return result
    """.trimIndent()

    /** [original] with one name changed and a comment added: what a character comparison still sees through. */
    override val copy = """
        # loendab sõnad
        def count_words(text):
            words = text.split()
            counts = {}
            for word in words:
                if word in counts:
                    counts[word] = counts[word] + 1
                else:
                    counts[word] = 1
            return counts
    """.trimIndent()

    override val unrelated = """
        import math

        class Circle:
            def __init__(self, radius):
                self.radius = radius

            def area(self):
                return math.pi * self.radius ** 2
    """.trimIndent()
}
