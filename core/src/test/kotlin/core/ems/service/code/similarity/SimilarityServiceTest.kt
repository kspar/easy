package core.ems.service.code.similarity

import core.exception.InvalidRequestException
import core.exception.ReqError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The comparison itself, without Spring or a database: what is compared with what, what comes back,
 * and how one check at a time is held to its budget.
 */
@Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SimilarityServiceTest {

    private fun SimilarityService.run(sources: List<SimilaritySource>, language: SimilarityLanguage, topN: Int = 100, owner: String = "teacher") =
        compare({ sources }, language, topN, owner)

    /** A provider whose pairs score and share what the test says, and which lets the test step into each pair. */
    private class Scripted(private val scores: (Long, Long) -> Pair<Double, Int>, private val onScore: () -> Unit = {}) : SimilarityProvider {
        override val id = "scripted"

        override fun prepare(sources: List<SimilaritySource>, language: SimilarityLanguage, workers: SimilarityWorkers) =
            PreparedCohort(language, sources.map { it.id }, emptyList()) { a, b ->
                onScore()
                val (score, shared) = scores(minOf(a, b), maxOf(a, b))
                SimilarityPair(minOf(a, b), maxOf(a, b), score, score, shared, emptyList())
            }
    }

    @Test
    fun `one text by three students is one group, no pair is one student's own, and blank or huge answers are skipped`() {
        val text = "SELECT name FROM people WHERE age > 18 ORDER BY name;"
        val other = "SELECT id, title FROM books WHERE year < 1950;"
        val sources = listOf(
            SimilaritySource(1, text, "ann"), SimilaritySource(2, text, "ben"), SimilaritySource(3, text, "cid"),
            SimilaritySource(4, other, "dan"), SimilaritySource(5, other, "eve"),
            SimilaritySource(6, text.replace(" ", "  "), "fay"),
            // Ann's own second attempt: compared with the group, which is hers too, so shown against another member.
            SimilaritySource(7, text.replace("18", "21"), "ann"),
            // One student's three identical attempts: not three students, so no group.
            SimilaritySource(8, "SELECT 1;", "gus"), SimilaritySource(9, "SELECT 1;", "gus"), SimilaritySource(10, "SELECT 1;", "gus"),
            // Blank answers would pair with each other at 100%; a huge one would hold the check up.
            SimilaritySource(11, "   ", "hal"), SimilaritySource(12, "   ", "ida"), SimilaritySource(13, "x".repeat(20_001), "jon"),
        )
        val outcome = SimilarityService(60).run(sources, SimilarityLanguage.SQL)

        assertEquals(listOf(listOf(1L, 2L, 3L)), outcome.groups)
        val pairs = outcome.pairs.associate { setOf(it.id1, it.id2) to it.maxSimilarity }
        // Nothing inside the group; the group compared once, through its first member.
        assertTrue(pairs.keys.none { it.size == 2 && it.all { id -> id in 1L..3L } }, "$pairs")
        assertEquals(1.0, pairs.getValue(setOf(4L, 5L)))
        assertTrue(pairs.getValue(setOf(1L, 6L)) in 0.5..0.99, "$pairs")
        assertTrue(setOf(2L, 6L) !in pairs && setOf(3L, 6L) !in pairs)
        val owner = sources.associate { it.id to it.owner }
        assertTrue(outcome.pairs.none { owner[it.id1] == owner[it.id2] }, "$pairs")
        assertTrue(setOf(2L, 7L) in pairs, "$pairs")
        assertEquals(
            listOf(SkippedSubmission(11, "TOO_SMALL"), SkippedSubmission(12, "TOO_SMALL"), SkippedSubmission(13, "TOO_LARGE")),
            outcome.skipped,
        )
    }

    @Test
    fun `a check while another runs is refused at once as busy, and the next one after it runs`() {
        val service = SimilarityService(60)
        val sources = (1L..3L).map { SimilaritySource(it, "SELECT $it;", "s$it") }
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val running = thread {
            service.compare({ inside.countDown(); release.await(); sources }, SimilarityLanguage.SQL, 100, "first")
        }
        assertTrue(inside.await(10, TimeUnit.SECONDS))

        val busy = assertThrows<InvalidRequestException> { service.run(sources, SimilarityLanguage.SQL) }
        assertEquals(ReqError.SIMILARITY_BUSY, busy.code)

        release.countDown()
        running.join()
        assertEquals(3, service.run(sources, SimilarityLanguage.SQL).pairs.size)
    }

    @Test
    fun `a check that runs out of time is refused by name, with its budget, instead of finishing`() {
        val service = SimilarityService(timeoutSec = 0)
        service.providerFor = { Scripted({ _, _ -> 0.5 to 5 }) }
        val sources = (1L..6L).map { SimilaritySource(it, "x$it", "s$it") }

        val refused = assertThrows<InvalidRequestException> { service.run(sources, SimilarityLanguage.TEXT) }
        assertEquals(ReqError.SIMILARITY_TIMEOUT, refused.code)
        assertEquals("budget_sec" to "0", refused.attributes.single())
    }

    @Test
    fun `progress says it is reading, then counts the pairs out of the pairs worth comparing, and only to whoever started it`() {
        val service = SimilarityService(60)
        val reached = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val scored = AtomicInteger()
        service.providerFor = {
            Scripted({ _, _ -> 0.5 to 5 }, onScore = {
                if (scored.incrementAndGet() == 2) {
                    reached.countDown()
                    resume.await()
                }
            })
        }
        // Ann's two attempts are never paired, so of the six pairs among four, five are compared.
        val sources = listOf(SimilaritySource(1, "a", "ann"), SimilaritySource(2, "b", "ann"), SimilaritySource(3, "c", "ben"), SimilaritySource(4, "d", "cid"))
        val loading = CountDownLatch(1)
        val loaded = CountDownLatch(1)
        val run = thread {
            service.compare({ loading.countDown(); loaded.await(); sources }, SimilarityLanguage.TEXT, 100, "teacher")
        }

        assertTrue(loading.await(10, TimeUnit.SECONDS))
        assertEquals(false, service.progress("teacher")!!.comparing)
        loaded.countDown()

        assertTrue(reached.await(10, TimeUnit.SECONDS))
        // The first pair is counted once its score returns, which on another worker can be a moment later.
        val counted = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (service.progress("teacher")!!.done < 1 && System.nanoTime() < counted) Thread.onSpinWait()
        val progress = service.progress("teacher")!!
        assertTrue(progress.comparing)
        assertEquals(5, progress.total)
        assertTrue(progress.done in 1..4, "$progress")
        assertNull(service.progress("another teacher"))

        resume.countDown()
        run.join()
        assertNull(service.progress("teacher"))
    }

    @Test
    fun `the pairs kept are the most shared and the highest scoring, and the answer says when more scored`() {
        // Pairs among four: two share much at a middling score, two share little at a high one, and one
        // scores too little to show at all.
        val scripted = mapOf(
            (1L to 2L) to (0.50 to 900),
            (1L to 3L) to (0.60 to 800),
            (1L to 4L) to (0.95 to 20),
            (2L to 3L) to (0.99 to 10),
            (2L to 4L) to (0.30 to 30),
            (3L to 4L) to (0.001 to 1),
        )
        val service = SimilarityService(60)
        service.providerFor = { Scripted({ a, b -> scripted.getValue(a to b) }) }
        val sources = (1L..4L).map { SimilaritySource(it, "x$it", "s$it") }

        val outcome = service.run(sources, SimilarityLanguage.TEXT, topN = 2)
        // The two by what they share, and the two by score, in the order of what they share.
        assertEquals(listOf(1L to 2L, 1L to 3L, 1L to 4L, 2L to 3L), outcome.pairs.map { it.id1 to it.id2 })
        // Five scored a share the page shows, and four came back.
        assertTrue(outcome.truncated)
    }

    @Test
    fun `JPlag says where a copy is, as the lines it takes up on each side`() {
        // The copied code ends on a one-line body, which the grammar ends on the line below.
        val code = """
            total = 0
            for value in values:
                total = total + value
            if values: print(total / len(values))
        """.trimIndent()
        val renamed = code.replace("values", "xs").replace("value", "x").replace("total", "s")
        val sources = listOf(
            SimilaritySource(1, code, "ann"),
            // The copy between three lines of its own and one more.
            SimilaritySource(2, "import math\nprint(math.pi)\nnumbers = [1, 2, 3]\n$renamed\nprint(s)", "ben"),
        )
        val pair = SimilarityService(60).run(sources, SimilarityLanguage.PYTHON).pairs.single()
        val lines = pair.matches.map { if (pair.id1 == 1L) it else SimilarityMatch(it.startLine2, it.endLine2, it.startLine1, it.endLine1, it.tokens) }

        assertEquals(1.0, pair.maxSimilarity)
        assertEquals(1, lines.minOf { it.startLine1 })
        assertEquals(4, lines.maxOf { it.endLine1 })
        assertEquals(4, lines.minOf { it.startLine2 })
        assertEquals(7, lines.maxOf { it.endLine2 })
    }
}
