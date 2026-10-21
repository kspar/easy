package core.ems.service.code.similarity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit

/**
 * What every [SimilarityProvider] owes the engine, whichever way it measures.
 *
 * The engine relies on these without checking them: it compares a group of identical submissions once
 * for all its members, so what a provider calls identical must score alike against everything; it asks
 * each pair once, in whichever order the triangle reaches it, so the answer must not depend on that
 * order; it shows a group by members other than the one compared, so details must be read from the
 * submissions it names. Each provider runs this suite with sources of its own.
 */
@Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
abstract class SimilarityProviderContractTest {

    internal abstract fun provider(): SimilarityProvider

    internal abstract val language: SimilarityLanguage

    /** A solution, a copy of it this provider is meant to see through, and an unrelated one. */
    abstract val original: String
    abstract val copy: String
    abstract val unrelated: String

    private fun <R> session(sources: List<SimilaritySource>, budgetSec: Long = 120, block: (ProviderSession) -> R): R =
        SimilarityWorkers(2, 64L shl 20).use { workers ->
            val run = RunContext(workers, Deadline.after(TimeUnit.SECONDS.toNanos(budgetSec)), budgetSec)
            provider().open(ProviderRequest(language, sources, null), run).use(block)
        }

    private fun sources(vararg solutions: Pair<Long, String>) = solutions.map { SimilaritySource(it.first, it.second) }

    @Test
    fun `a copy scores above an unrelated solution, and the same whichever way the pair is asked`() {
        session(sources(1L to original, 2L to copy, 3L to unrelated)) { session ->
            val cohort = session.prepare()
            session.ready(cohort.usable)
            val copied = session.score(1, 2)!!
            val asked = session.score(2, 1)!!
            val apart = session.score(1, 3)

            assertTrue(copied.score > (apart?.score ?: 0.0), "copy ${copied.score}, unrelated ${apart?.score}")
            assertEquals(setOf(1L, 2L), setOf(copied.first, copied.second))
            // Oriented by the provider, not by the order the engine happened to ask in.
            assertEquals(copied.first, asked.first)
            assertEquals(copied.score, asked.score)
            assertEquals(copied.similarity, asked.similarity)
            assertEquals(copied.rank, asked.rank)
        }
    }

    @Test
    fun `the score is a share, and the symmetric similarity is never above it`() {
        session(sources(1L to original, 2L to copy, 3L to unrelated)) { session ->
            session.ready(session.prepare().usable)
            for ((a, b) in listOf(1L to 2L, 1L to 3L, 2L to 3L)) {
                val pair = session.score(a, b) ?: continue
                assertTrue(pair.score in 0.0..1.0, "score ${pair.score} for $a-$b")
                assertTrue(pair.similarity in 0.0..pair.score, "similarity ${pair.similarity} above score ${pair.score} for $a-$b")
                assertTrue(pair.rank >= 0, "rank ${pair.rank} for $a-$b")
            }
        }
    }

    /**
     * What the provider declares is what it does: the page paints a provider's matches as the whole
     * answer only when it says they are, and works out the shared code itself when it says they are not.
     */
    @Test
    fun `details are read from the submissions shown, oriented as the pair is`() {
        // Moved down three lines, so that a side read from the other submission lands outside its own.
        val texts = mapOf(1L to original, 2L to "\n\n\n" + copy)
        session(sources(1L to texts.getValue(1L), 2L to texts.getValue(2L))) { session ->
            session.ready(session.prepare().usable)
            val pair = session.score(1, 2)!!
            val matches = session.details(pair, pair.first, pair.second)
            if (!provider().matchRanges) {
                assertEquals(emptyList<SimilarityMatch>(), matches)
                return@session
            }
            assertTrue(matches.isNotEmpty(), "a copy matched nowhere")
            val lines1 = texts.getValue(pair.first).lines().size
            val lines2 = texts.getValue(pair.second).lines().size
            for (match in matches) {
                assertTrue(match.startLine1 in 1..match.endLine1 && match.endLine1 <= lines1, "$match outside 1..$lines1")
                assertTrue(match.startLine2 in 1..match.endLine2 && match.endLine2 <= lines2, "$match outside 1..$lines2")
            }
        }
    }

    @Test
    fun `what is called identical scores alike against everything`() {
        // Laid out differently, the same otherwise: the engine compares only one of the two.
        val relaid = original.lines().joinToString("\n\n")
        session(sources(1L to original, 2L to relaid, 3L to unrelated, 4L to copy)) { session ->
            val cohort = session.prepare()
            session.ready(cohort.usable)
            if (cohort.identical(1, 2)) {
                assertEquals(cohort.groupKey(1), cohort.groupKey(2))
                for (other in listOf(3L, 4L)) {
                    val viaFirst = session.score(1, other)
                    val viaSecond = session.score(2, other)
                    assertEquals(viaFirst?.score, viaSecond?.score, "against $other")
                    assertEquals(viaFirst?.similarity, viaSecond?.similarity, "against $other")
                    assertEquals(viaFirst?.rank, viaSecond?.rank, "against $other")
                }
            }
            assertFalse(cohort.identical(1, 3), "an unrelated solution was called identical")
        }
    }

    @Test
    fun `an empty solution is refused, and the rest stay usable in the cohort's order`() {
        session(sources(3L to original, 1L to "", 2L to copy)) { session ->
            val cohort = session.prepare()
            assertEquals(listOf(3L, 2L), cohort.usable)
            assertEquals(listOf(1L), cohort.skipped.map { it.id })
        }
    }

    @Test
    fun `a spent budget refuses the cohort rather than preparing it`() {
        assertThrows<SimilarityTimedOut> {
            session(sources(1L to original, 2L to copy), budgetSec = 0) { it.prepare() }
        }
    }
}

class JPlagProviderContractTest : SimilarityProviderContractTest() {
    override fun provider(): SimilarityProvider = JPlagProvider(maxSolutionTokens = 10_000, maxRepetition = 1_000_000)

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

    /** [original] with every identifier renamed, which a token comparison is meant not to notice. */
    override val copy = """
        def tally(s):
            items = s.split()
            acc = {}
            for item in items:
                if item in acc:
                    acc[item] = acc[item] + 1
                else:
                    acc[item] = 1
            return acc
    """.trimIndent()

    override val unrelated = """
        import math

        class Circle:
            def __init__(self, radius):
                self.radius = radius

            def area(self):
                return math.pi * self.radius ** 2

            def circumference(self):
                return 2 * math.pi * self.radius
    """.trimIndent()
}
