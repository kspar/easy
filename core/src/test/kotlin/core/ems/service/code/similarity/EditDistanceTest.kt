package core.ems.service.code.similarity

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

    /**
     * fuzzywuzzy's `FuzzySearch.ratio` for each pair - the old check's "Levenshtein" score - recorded
     * from fuzzywuzzy 1.4.0 itself, so the library need not be a dependency to hold the new code to it.
     * The ratio is 2·LCS/(L1+L2), and [lcs] is held to a plain table below; these pin what is left: the
     * rounding, the empty texts, and what a character is. Each pair is here for one of those.
     */
    private val recordedRatios = listOf(
        Triple("", "", 0),                      // 0/0 rounds to 0, not to 100
        Triple("", "abc", 0),
        Triple("abc", "abc", 100),
        Triple("kitten", "sitting", 62),
        Triple("x", "x" + "y".repeat(14), 13),  // 12.5: half rounds up
        Triple(emoji, emoji + "a", 80),         // UTF-16 units, 4/5, not code points (2/3)
        Triple("a" + Char(0x308), Char(0xE4).toString(), 0), // no Unicode normalisation
        Triple("Abc", "abc", 67),               // no case folding
        Triple("a = 1", "a=1", 75),             // layout counts
        Triple("def f(x):\n    return x + 1\n", "def g(y):\n    return y + 1\n", 89),
        Triple("SELECT name FROM people WHERE age > 18;", "select name from people where age>18", 56),
    )

    private val emoji get() = String(Character.toChars(0x1F600))

    @Test
    fun `the ratio is fuzzywuzzy's, as the old check reported it`() {
        for ((a, b, expected) in recordedRatios) {
            assertEquals(expected, Math.round(100 * EditDistance.ratio(a, b)).toInt(), "'$a' vs '$b'")
            assertEquals(expected, Math.round(100 * EditDistance.ratio(b, a)).toInt(), "'$b' vs '$a'")
        }
    }

    @Test
    fun `the longest common subsequence is exact, across word boundaries and outside ASCII`() {
        for (a in texts) for (b in texts) {
            assertEquals(lcsByTable(a, b), EditDistance.lcs(a, b), "${a.length} vs ${b.length}")
        }
    }

    @Test
    fun `the bound is never below the real subsequence, so no pair it rules out could have ranked`() {
        // Every pair of the texts above, in both directions, plus texts made of the same characters in
        // another order - where the q-gram part of the bound has to do the work the character count cannot.
        val shuffled = texts.filter { it.length > 3 }.map { it.toList().shuffled(Random(1667)).joinToString("") }
        val all = texts + shuffled
        for (a in all) for (b in all) {
            val bound = EditDistance.lcsBound(EditDistance.profile(a), EditDistance.profile(b))
            val real = EditDistance.lcs(a, b)
            assertTrue(bound >= real, "bound $bound below lcs $real for ${a.length} vs ${b.length}")
            assertTrue(EditDistance.ratioBound(EditDistance.profile(a), EditDistance.profile(b)) >= EditDistance.ratio(a, b))
        }
    }

    @Test
    fun `the quick bound is never below the exact one, nor the exact one below the score`() {
        // The optimised mode orders by the quick bound and rules out by both: either one too low drops a
        // pair that could have ranked.
        val sources = texts.filter { it.isNotBlank() }.distinct().mapIndexed { i, text -> SimilaritySource(i + 1L, text) }
        SimilarityWorkers(1, 1L shl 20).use { workers ->
            val run = RunContext(workers, Deadline.after(TimeUnit.SECONDS.toNanos(60)), 60)
            EditDistanceProvider().open(ProviderRequest(SimilarityLanguage.TEXT, sources, null), run).use { session ->
                val ids = session.prepare().usable
                for (a in ids) for (b in ids) {
                    if (a == b) continue
                    val quick = session.quickBound(a, b)!!
                    val exact = session.bound(a, b)!!
                    val real = session.score(a, b)!!
                    for ((name, values) in mapOf("score" to listOf(quick.score, exact.score, real.score), "rank" to listOf(quick.rank, exact.rank, real.rank))) {
                        assertTrue(values[0] >= values[1] && values[1] >= values[2], "$name for $a-$b: quick, exact, real = $values")
                    }
                }
            }
        }
    }

    @Test
    fun `the quick bound is about the texts, so copies are ordered before unrelated pairs of like length`() {
        // The optimised mode compares pairs in this order and may stop part way: from sizes alone, a padded
        // copy came after every unrelated pair closer in length, and a run cut short could miss it.
        val code = (1..30).map { seed ->
            val random = Random(seed)
            val word = { String(CharArray(5) { 'a' + random.nextInt(26) }) }
            (1..40).joinToString("\n") { "    ${word()} = ${word()}(${word()})" }
        }
        val padded = code[0] + "\n# kopeeritud\n# ja natuke muudetud\n" + "    print('valmis')\n".repeat(5)
        val renamed = code[1].replace("(", "[").replace(")", "]")
        val sources = code.mapIndexed { i, text -> SimilaritySource(i + 1L, text) } +
            listOf(SimilaritySource(101, padded), SimilaritySource(102, renamed))
        SimilarityWorkers(1, 1L shl 20).use { workers ->
            val run = RunContext(workers, Deadline.after(TimeUnit.SECONDS.toNanos(60)), 60)
            EditDistanceProvider().open(ProviderRequest(SimilarityLanguage.TEXT, sources, null), run).use { session ->
                val ids = session.prepare().usable
                val copies = setOf(1L to 101L, 2L to 102L)
                val unrelated = ids.flatMap { a -> ids.filter { it > a }.map { a to it } } - copies
                val highestUnrelated = unrelated.maxOf { (a, b) -> session.quickBound(a, b)!!.rank }
                for ((a, b) in copies) {
                    val rank = session.quickBound(a, b)!!.rank
                    assertTrue(rank > highestUnrelated, "copy $a-$b quickly bounded at $rank, an unrelated pair at $highestUnrelated")
                }
            }
        }
    }

    @Test
    fun `a pair scored after another was bounded gets its own Dice, not the one kept from the bound`() {
        // The optimised mode scores a pair right after bounding it, on the same thread, and the score takes
        // that bound's Dice; any other pair must not.
        // 1-2 share every bigram but one, 1-3 three of seven: two Dice values far apart.
        val sources = listOf(SimilaritySource(1, "abcdefgh"), SimilaritySource(2, "abcdefgx"), SimilaritySource(3, "qrstabcd"))
        SimilarityWorkers(1, 1L shl 20).use { workers ->
            val run = RunContext(workers, Deadline.after(TimeUnit.SECONDS.toNanos(60)), 60)
            val fresh = EditDistanceProvider().open(ProviderRequest(SimilarityLanguage.TEXT, sources, null), run).use { session ->
                session.prepare()
                session.score(1, 3)!!.similarity
            }
            EditDistanceProvider().open(ProviderRequest(SimilarityLanguage.TEXT, sources, null), run).use { session ->
                session.prepare()
                session.bound(1, 2)
                // The mean of the two measures, which a wrong Dice moves whichever measure is the higher.
                assertEquals(fresh, session.score(1, 3)!!.similarity)
            }
        }
    }

    @Test
    fun `the bound tells reordered text from a copy, which counting characters cannot`() {
        val code = "def f(x):\n    return x * 2 + 1\n\nprint(f(3))\n".repeat(4)
        val reordered = code.toList().sorted().joinToString("")
        val bound = EditDistance.ratioBound(EditDistance.profile(code), EditDistance.profile(reordered))
        assertEquals(1.0, EditDistance.ratioBound(EditDistance.profile(code), EditDistance.profile(code)))
        // Counting characters alone bounds it at 1.0: the same characters, every one of them shared.
        assertTrue(bound < 0.75, "the same characters sorted are bounded at $bound")
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
    fun `the cap counts UTF-8 bytes, as the database the estimate reads does, never fewer than the units compared`() {
        // Six emoji are twelve UTF-16 units, six characters and 24 bytes to Postgres's octet_length. Counted
        // in characters, the estimate priced them at half what the comparison costs; counted differently
        // from the estimate, the run skipped what it had priced into every pair.
        val emoji = String(Character.toChars(0x1F600)).repeat(6)
        assertEquals(24, EditDistanceProvider.utf8Length(emoji))
        // One of each width: ASCII, Estonian, the euro sign, a combining mark, an emoji.
        for (text in listOf("", "abc", "õäöü", "€", "a" + Char(0x308), emoji, "x = 'õ€' + $emoji")) {
            assertEquals(text.toByteArray(Charsets.UTF_8).size, EditDistanceProvider.utf8Length(text))
            assertTrue(EditDistanceProvider.utf8Length(text) >= text.length)
        }
        val provider = EditDistanceProvider(maxSolutionBytes = 24)
        assertTrue(provider.compares(24))
        assertTrue(!provider.compares(25))
        SimilarityWorkers(1, 1L shl 20).use { workers ->
            val run = RunContext(workers, Deadline.after(TimeUnit.SECONDS.toNanos(60)), 60)
            val sources = listOf(SimilaritySource(1, emoji), SimilaritySource(2, emoji + "x"), SimilaritySource(3, "õ".repeat(12)))
            provider.open(ProviderRequest(SimilarityLanguage.TEXT, sources, null), run).use { session ->
                val cohort = session.prepare()
                assertEquals(listOf(1L, 3L), cohort.usable)
                assertEquals(listOf(SkippedSubmission(2, "TOO_LARGE")), cohort.skipped)
            }
        }
    }

    @Test
    fun `a solution above the character cap is skipped as too large, and a blank one as too small`() {
        val provider = EditDistanceProvider(maxSolutionBytes = 10)
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
