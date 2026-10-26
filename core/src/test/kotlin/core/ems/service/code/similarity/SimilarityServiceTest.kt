package core.ems.service.code.similarity

import de.jplag.JPlagComparison
import de.jplag.Language
import de.jplag.ParsingException
import de.jplag.Submission
import de.jplag.SubmissionSet
import de.jplag.Token
import de.jplag.comparison.LongestCommonSubsequenceSearch
import de.jplag.options.JPlagOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Behaviour of the similarity engine, driven through the real [SimilarityService].
 *
 * No Spring context: the service takes four configuration values and nothing else, which is the
 * reason its configuration is constructor-injected rather than read from the environment. That
 * keeps this suite in the sub-second tier and means every case below runs on the code that ships.
 * What each frontend makes of a solution is held to account in its own suite; this one is about what
 * the service does with the tokens, whichever language they came from.
 *
 * The replaced version of this file was a spike that re-declared its own copies of the adapter and
 * the submission wrapper, so it passed whatever production did — including while production was
 * returning HTTP 500 for any exercise with a template.
 */
@Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SimilarityServiceTest {

    private fun service(
        maxSolutionTokens: Int = 10_000,
        maxRepetition: Long = 1_000_000,
        timeoutSec: Long = 120,
        clock: Ticker = Ticker.SYSTEM,
    ) = SimilarityService(
        maxSubmissions = 50, maxSolutionTokens = maxSolutionTokens, maxRepetition = maxRepetition,
        timeoutSec = timeoutSec, ticker = clock,
    )

    private val service = service()

    /**
     * A service whose tokeniser fails on one input, [CHOKES] or [OVERFLOWS], the way a real one can.
     *
     * No real input reaches either failure reliably any more. JPlag's listener throwing needs error
     * recovery to enter a rule it cannot start; and nothing inside the default limits overflows the
     * pool's 64 MB stack, while what does - past a raised token cap - takes the better part of a minute
     * of prediction to get there. The catches for both are still what keeps one submission from being a
     * 500 for the whole cohort, so without a way in they could be deleted with every test green.
     */
    private fun failing(): SimilarityService = service().also { failing ->
        failing.tokenise = { language, id, solution, maxTokens ->
            when (solution) {
                CHOKES -> throw SimilarityParseException("could not tokenise ${id.name}", IllegalStateException())
                OVERFLOWS -> throw StackOverflowError()
                else -> language.read(id, solution, maxTokens)
            }
        }
    }

    private val original = """
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

    /** [original] with every identifier renamed — the case a text comparison cannot see. */
    private val renamed = """
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

    private val unrelated = """
        import math

        class Circle:
            def __init__(self, radius):
                self.radius = radius

            def area(self):
                return math.pi * self.radius ** 2

            def circumference(self):
                return 2 * math.pi * self.radius
    """.trimIndent()

    private fun compare(
        vararg sources: Pair<Long, String>,
        language: SimilarityLanguage = SimilarityLanguage.PYTHON,
        on: SimilarityService = service,
        topN: Int = 100,
    ) = on.compare(
        { sources.map { SimilaritySource(it.first, it.second) } },
        { it },
        language,
        null,
        topN,
    ).outcome

    private fun SimilarityOutcome.pair(id1: Long, id2: Long) =
        pairs.single { setOf(it.id1, it.id2) == setOf(id1, id2) }

    private fun SimilarityOutcome.pairOrNull(id1: Long, id2: Long) =
        pairs.singleOrNull { setOf(it.id1, it.id2) == setOf(id1, id2) }

    // --- 1. the property the whole change exists for ---

    @Test
    fun `renaming every identifier does not lower a token-based score, while unrelated code stays low`() {
        val outcome = compare(1L to original, 2L to renamed, 3L to unrelated)

        assertTrue(
            outcome.pair(1L, 2L).similarity > 0.9,
            "a pure rename should still read as a copy, but scored ${outcome.pair(1L, 2L).similarity}",
        )
        // Absent rather than merely low. A comparison that matched nothing is not a finding, and
        // returning it pads the teacher's list with rows that exist only because the cohort does.
        assertNull(outcome.pairOrNull(1L, 3L), "an unrelated pair was reported: ${outcome.pairs}")
        assertNull(outcome.pairOrNull(2L, 3L), "an unrelated pair was reported: ${outcome.pairs}")
    }

    @Test
    fun `an SQL lab copied with new aliases and new layout is found, and honest labs rank below it`() {
        // The SQL frontend's promise, end to end: names and layout are not tokens, so the copy is the
        // same token stream as its source, while two labs written separately share only fragments.
        val lab = """
            SELECT e.nimetus, COUNT(*) AS arv
            FROM eriala e JOIN tudeng t ON t.eriala_id = e.id
            GROUP BY e.nimetus HAVING COUNT(*) >= 5 ORDER BY arv DESC;

            CREATE OR REPLACE FUNCTION ainepunkte(p_tudeng INTEGER) RETURNS INTEGER AS $$
            DECLARE summa INTEGER;
            BEGIN
              SELECT COALESCE(SUM(k.ainepunktid), 0) INTO summa
              FROM registreering r JOIN kursus k ON k.id = r.kursus_id
              WHERE r.tudeng_id = p_tudeng AND r.hinne > 0;
              RETURN summa;
            END;
            $$ LANGUAGE plpgsql;
        """.trimIndent()
        val copy = """
            select m.nimetus, count(*) as kokku from eriala m join tudeng s on s.eriala_id = m.id
            group by m.nimetus having count(*) >= 5 order by kokku desc;
            -- teine ülesanne
            create or replace function ainepunkte(x integer) returns integer as ${'$'}body${'$'}
            declare kokku integer; begin
            select coalesce(sum(a.ainepunktid), 0) into kokku from registreering b join kursus a on a.id = b.kursus_id
            where b.tudeng_id = x and b.hinne > 0; return kokku; end; ${'$'}body${'$'} language plpgsql;
        """.trimIndent()
        val honest = """
            WITH loend AS (SELECT eriala_id, COUNT(*) AS arv FROM tudeng GROUP BY eriala_id)
            SELECT e.nimetus, l.arv FROM eriala e JOIN loend l ON l.eriala_id = e.id WHERE l.arv >= 5 ORDER BY l.arv DESC;

            CREATE FUNCTION ainepunkte(id_in INTEGER) RETURNS INTEGER AS $$
            DECLARE tulemus INTEGER := 0; rida RECORD;
            BEGIN
              FOR rida IN SELECT k.ainepunktid FROM kursus k, registreering r
                          WHERE r.kursus_id = k.id AND r.tudeng_id = id_in AND r.hinne > 0 LOOP
                tulemus := tulemus + rida.ainepunktid;
              END LOOP;
              RETURN tulemus;
            END;
            $$ LANGUAGE plpgsql;
        """.trimIndent()

        val outcome = compare(1L to lab, 2L to copy, 3L to honest, language = SimilarityLanguage.SQL)

        assertEquals(SimilarityLanguage.SQL, outcome.language)
        assertEquals(1.0, outcome.pair(1L, 2L).similarity, 1e-9, "the copy is not the same token stream")
        assertEquals(setOf(1L, 2L), outcome.pairs.first().let { setOf(it.id1, it.id2) }, "the copy is not first")
        val honestPairs = outcome.pairs.filter { 3L in setOf(it.id1, it.id2) }
        assertTrue(honestPairs.all { it.maxSimilarity < 0.6 }, "honest labs scored like copies: $honestPairs")
    }

    @Test
    fun `a Java copy with its names changed is found, and one with a syntax error still is`() {
        val original = """
            import java.util.Scanner;

            public class Main {
                public static void main(String[] args) {
                    Scanner sc = new Scanner(System.in);
                    int n = sc.nextInt();
                    int[] a = new int[n];
                    for (int i = 0; i < n; i++) a[i] = sc.nextInt();
                    int max = a[0];
                    long sum = 0;
                    for (int v : a) {
                        if (v > max) max = v;
                        sum += v;
                    }
                    System.out.println(max + " " + (double) sum / n);
                }
            }
        """.trimIndent()
        val renamed = original.replace("Main", "Lahendus").replace(" sc", " lugeja").replace("sc.", "lugeja.")
            .replace("max", "suurim").replace("sum", "kokku").replace(" v", " arv").replace("(v", "(arv")
        // Recovered, not refused - the same rule as Python's, so one broken line is no way out.
        val broken = renamed.replace("int n = lugeja.nextInt();", "int n = lugeja.nextInt()")

        // One copy at a time: all three are the same tokens, and together would be one group.
        val withRenamed = compare(1L to original, 2L to renamed, language = SimilarityLanguage.JAVA)
        val withBroken = compare(1L to original, 3L to broken, language = SimilarityLanguage.JAVA)

        assertEquals(emptyList<SkippedSubmission>(), withBroken.skipped)
        assertTrue(withRenamed.pair(1L, 2L).similarity > 0.95, "the renamed copy scored ${withRenamed.pair(1L, 2L)}")
        assertTrue(withBroken.pair(1L, 3L).maxSimilarity > 0.8, "the broken copy scored ${withBroken.pair(1L, 3L)}")
    }

    @Test
    fun `nothing is written to the filesystem`() {
        // The File instances the service builds are identifiers, not paths, and JPlag resolves them
        // against the working directory — so the whole directory is listed before and after, rather
        // than the three names this code happens to use today. A failing submission is included
        // because that is where JPlag writes when it does: with debugParser on, a CANNOT_PARSE
        // submission is copied into `errors/`. Comparing listings also tolerates a file a killed run
        // of the test below left behind, where checking for absent names failed on it. Java is
        // included because its frontend is javac, which writes class files when it is asked to compile.
        //
        // The whole tree, so a write into a directory that already exists (a leftover `errors/`) is
        // seen too — minus what other writers own. Logback rolls `easy.log` here, from this JVM and
        // from the dev core through the bind mount, and a rollover between the two listings added an
        // archive name and failed the test over a write the service never made; `build/`,
        // `.gradle/` and `.kotlin/` belong to the build running this test.
        val workingDir = File(".").absoluteFile
        val othersOwn = setOf("build", ".gradle", ".kotlin")
        fun listing() = workingDir.walkTopDown()
            .onEnter { it == workingDir || it.name !in othersOwn }
            .filterNot { it.name.startsWith("easy.log") }
            .map { it.relativeTo(workingDir).path }
            .toSet()
        val before = listing()
        val outcome = compare(1L to original, 2L to renamed, 3L to CHOKES, on = failing())
        assertEquals(listOf("CANNOT_PARSE"), outcome.skipped.map { it.reason }, "the failing submission did not fail")
        compare(1L to "class A { void f() { g(); h(); } }", 2L to "class B { void f() { g(); h(); } }", language = SimilarityLanguage.JAVA)
        assertEquals(emptySet<String>(), listing() - before, "created in $workingDir")
    }

    @Test
    fun `nothing is read from the filesystem either, even when a file is sitting at the identifier path`() {
        // The test above only proves nothing was *created*. This one proves nothing is *read*, which
        // is the half the design actually rests on and the half a non-existent file cannot
        // demonstrate - a read of a missing file throws, so "it worked" would prove nothing.
        //
        // The identifiers are relative Files, so they resolve against the JVM working directory and
        // a real file can be planted at exactly the path JPlag would open if it ever opened one.
        // Asserted in both directions: one alone would pass against a version that scored everything
        // the same way regardless of input.
        val planted = listOf(File("submission-1"), File("submission-2"))
        // Backstop for a run killed before the `finally`: the files are planted in core/, where
        // nothing ignores them, and would otherwise stay behind as untracked files.
        planted.forEach { it.deleteOnExit() }
        try {
            // Disk says two unrelated programs; memory says the same program twice.
            planted[0].writeText(original + "\n")
            planted[1].writeText(unrelated + "\n")
            assertTrue(planted.all { it.exists() }, "this test is meaningless unless the files exist")

            val identical = compare(1L to original, 2L to original)
            assertEquals(
                1.0, identical.pair(1L, 2L).similarity, 1e-9,
                "two identical in-memory solutions scored below 1.0 while unrelated files sat at " +
                        "those paths, so the comparison read the disk",
            )

            // Now the converse: disk says identical, memory says unrelated.
            planted[1].writeText(original + "\n")
            val differing = compare(1L to original, 2L to unrelated)
            assertNull(
                differing.pairOrNull(1L, 2L),
                "two unrelated in-memory solutions scored while identical files sat at those paths, " +
                        "so the comparison read the disk",
            )
        } finally {
            planted.forEach { it.delete() }
        }
        // Outside the `finally`, so a failure above is reported as itself rather than as this.
        assertTrue(planted.none { it.exists() }, "the planted files could not be deleted: $planted")
    }

    // --- 2. what gets dropped, and whether anyone is told ---

    @Test
    fun `a submission with fewer tokens than the match length is skipped rather than silently scored`() {
        val outcome = compare(1L to original, 2L to renamed, 3L to "x = 1")

        assertEquals(listOf(3L), outcome.skipped.map { it.id })
        assertEquals("TOO_SMALL", outcome.skipped.single().reason)
        // Asserting the score, not just that something came back: with the default threshold JPlag
        // retains every comparison, so `isNotEmpty` is true however badly the pair scored.
        assertTrue(
            outcome.pair(1L, 2L).similarity > 0.9,
            "the usable pair was lost or degraded along with the small one",
        )
    }

    @Test
    fun `a recovered syntax error does not exclude a submission from detection`() {
        // ANTLR recovers from this and JPlag would still compare it. Excluding it would hand a
        // plagiarist an opt-out: append one line the grammar dislikes and vanish from the results.
        val broken = original + "\n    this is not python at all ((("
        val outcome = compare(1L to original, 2L to broken)

        assertEquals(emptyList<Long>(), outcome.skipped.map { it.id }, "a recovered error dropped the submission")
        assertTrue(outcome.pair(1L, 2L).similarity > 0.5)
    }

    @Test
    fun `a submission that overflows the parser stack is skipped as unparseable, not a 500`() {
        // Without the catch in tokeniseOne the Error escapes the pool thread, compare() rethrows it,
        // the generic handler answers an empty 500, and one student's submission takes the whole cohort
        // down. Reached through the tokeniser hook; see [failing] for why no real input is used.
        val outcome = compare(1L to original, 2L to renamed, 3L to OVERFLOWS, on = failing())

        assertEquals(listOf(3L), outcome.skipped.map { it.id })
        assertEquals("CANNOT_PARSE", outcome.skipped.single().reason)
        assertTrue(outcome.pair(1L, 2L).similarity > 0.9, "the usable pair was lost with the overflow")
    }

    @Test
    fun `an OutOfMemoryError while tokenising reaches the caller as itself, not as a skip`() {
        // The opposite of the overflow above, and the reason the catches are narrow: filed as a
        // CANNOT_PARSE the endpoint would answer 200 over a dead heap, every remaining submission
        // "skipped", indistinguishable from a cohort that genuinely would not parse.
        val starving = service().also { starving ->
            starving.tokenise = { language, id, solution, maxTokens ->
                if (solution == renamed) throw OutOfMemoryError("synthetic") else language.read(id, solution, maxTokens)
            }
        }
        // Caught by hand: assertThrows treats an OutOfMemoryError as unrecoverable and rethrows it.
        val thrown = try {
            compare(1L to original, 2L to renamed, 3L to unrelated, on = starving)
            null
        } catch (e: OutOfMemoryError) {
            e
        }
        assertEquals("synthetic", thrown?.message, "no OutOfMemoryError reached the caller")
    }

    @Test
    fun `valid Python nested as deep as CPython allows is compared, or refused by name`() {
        // The deepest of each that CPython 3.14 compiles and runs. On the JVM's default 1 MB stack the
        // `not` and `lambda` chains overflowed the parser, and under LL prediction the minuses took
        // minutes, so each was dropped as unparseable - one appended line took a copy out of the
        // comparison while the code still passed its tests.
        val unary = renamed + "\n_ = " + "-".repeat(5_966) + "1\n"
        val negations = renamed + "\n_ = " + "not ".repeat(5_967) + "True\n"
        val lambdas = renamed + "\n_ = " + "lambda: ".repeat(2_983) + "1\n"
        val outcome = compare(1L to original, 2L to unary, 3L to negations, 4L to lambdas)

        // The lambda chain parses now, and is then refused for what it tokenises to: 2,983 LAMBDA tokens
        // in a row, the shape that stalls a pair. That is the bound working, and it is a named skip.
        assertEquals(listOf(SkippedSubmission(4L, "TOO_REPETITIVE")), outcome.skipped)
        for (copy in 2L..3L) {
            assertTrue(outcome.pair(1L, copy).similarity > 0.9, "copy $copy was not found: ${outcome.pairs}")
        }
    }

    @Test
    fun `brackets nested past CPython's limit are refused unparsed, and at it are compared`() {
        // Nesting costs the parser without costing length: 5,000 brackets took over two minutes, well
        // inside the token cap. CPython refuses more than 200, so nothing deeper can pass a test, and
        // nothing deeper is parsed. 200 is compared like anything else.
        val atTheLimit = renamed + "\n_ = " + "[".repeat(200) + "]".repeat(200) + "\n"
        val pastIt = renamed + "\n_ = " + "[".repeat(201) + "]".repeat(201) + "\n"
        val farPastIt = renamed + "\n_ = " + "(".repeat(4_000) + "1" + ")".repeat(4_000) + "\n"

        val started = System.nanoTime()
        val outcome = compare(1L to original, 2L to atTheLimit, 3L to pastIt, 4L to farPastIt)
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(listOf(SkippedSubmission(3L, "TOO_COMPLEX"), SkippedSubmission(4L, "TOO_COMPLEX")), outcome.skipped)
        assertTrue(outcome.pair(1L, 2L).similarity > 0.9, "the copy at the limit was not compared")
        assertTrue(tookMs < 10_000, "took ${tookMs}ms, so the deep one was parsed")
    }

    @Test
    fun `nesting the parser would take minutes over is skipped by name as TOO_COMPLEX, and quickly`() {
        // Under LL prediction, 500 nested minuses took 18 s to parse and 1,000 over two minutes, in a
        // few hundred characters no size cap sees. SLL parses them at once, but one line of syntax the
        // grammar does not know - a walrus, here - sends the whole file to the LL fallback, and that is
        // where the prediction budget has to stop it. This one would take most of a minute unbounded.
        val nestedAndUnknownSyntax = renamed + "\nprint(y := 1)\n_ = " + "-".repeat(600) + "1\n"

        val started = System.nanoTime()
        val outcome = compare(1L to original, 2L to nestedAndUnknownSyntax, 3L to renamed)
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(listOf(SkippedSubmission(2L, "TOO_COMPLEX")), outcome.skipped)
        assertTrue(outcome.pair(1L, 3L).similarity > 0.9, "the usable pair was lost with the nested one")
        assertTrue(tookMs < 10_000, "the parse was allowed to run for ${tookMs}ms")
    }

    @Test
    fun `the deepest nesting CPython accepts, sent down the LL fallback, is refused within seconds`() {
        // The same, at CPython's own limit of 5,966 minuses. There the prediction budget was reached only
        // after 67 s, with 1.3 GB on the heap - per submission, on every pool thread at once - because a
        // closure costs more the deeper the nesting it works through. The wall-clock limit is what holds.
        val deepest = renamed + "\nprint(y := 1)\n_ = " + "-".repeat(5_966) + "1\n"

        val started = System.nanoTime()
        val outcome = compare(1L to original, 2L to deepest, 3L to renamed)
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(listOf(SkippedSubmission(2L, "TOO_COMPLEX")), outcome.skipped)
        assertTrue(tookMs < 10_000, "the parse was allowed to run for ${tookMs}ms")
    }

    @Test
    fun `a tokeniser that throws costs that submission or the template, not the comparison`() {
        // No real input reliably makes a frontend throw, so the tokeniser is swapped for one that
        // does, for one submission and for the template. Without the SimilarityParseException catch
        // the exception leaves the pool, compare() rethrows it, and one student's submission is a 500
        // for the whole cohort; for the template, every run on the exercise is.
        val sources = listOf(
            SimilaritySource(1L, original), SimilaritySource(2L, renamed), SimilaritySource(3L, CHOKES),
        )

        val outcome = failing().compare({ sources }, { it }, SimilarityLanguage.PYTHON, CHOKES, 100).outcome

        assertEquals(listOf(3L), outcome.skipped.map { it.id })
        assertEquals("CANNOT_PARSE", outcome.skipped.single().reason)
        assertTrue(outcome.pair(1L, 2L).similarity > 0.9, "the usable pair was lost with the failure")
    }

    @Test
    fun `a template that overflows the parser stack is dropped instead of failing the comparison`() {
        // The template is tokenised on the pool like a submission, so it is contained like one.
        // Without that, the Error escapes to the generic handler and every run on the exercise is an
        // empty 500 - over a problem in the template alone.
        val outcome = failing().compare(
            { listOf(SimilaritySource(1L, original), SimilaritySource(2L, renamed)) },
            { it }, SimilarityLanguage.PYTHON, OVERFLOWS, 100,
        ).outcome

        assertEquals(emptyList<SkippedSubmission>(), outcome.skipped)
        assertTrue(outcome.pair(1L, 2L).similarity > 0.9, "the pair was lost with the template")
    }

    @Test
    fun `the template is tokenised on the pool's deep stack, not the caller's`() {
        // A template nested deeper than a small stack follows, and the same code as both solutions -
        // so if it is used they share nothing but what the template already has, and if its parse
        // overflowed and it was dropped, the pair stays a copy. Run on a 256 KB caller, where it did
        // overflow while templates were parsed on the calling thread.
        val template = original + "\n_ = " + "not ".repeat(5_000) + "True\n"
        var outcome: SimilarityOutcome? = null
        val smallStack = Thread(null, {
            outcome = service.compare(
                { listOf(SimilaritySource(1L, original + "\nprint(1)\n"), SimilaritySource(2L, renamed + "\nprint(2)\n")) },
                { it }, SimilarityLanguage.PYTHON, template, 100,
            ).outcome
        }, "small-stack", 256L * 1024)
        smallStack.start()
        smallStack.join()

        val left = outcome!!.pairOrNull(1L, 2L)?.similarity ?: 0.0
        assertTrue(left < 0.5, "the template was not subtracted - dropped, so its parse failed: $left")
    }

    @Test
    fun `SQL is matched on its own minimum run, not another language's`() {
        // Two solutions sharing one run of exactly eleven tokens, and otherwise nothing that lines up:
        // one short of SQL's twelve, so no match - where at Python's six it would be one. Two tokens
        // more in the shared run, and it is.
        fun cohort(shared: String) = compare(
            1L to "CREATE TABLE x (y INT);\n$shared\nDROP VIEW v;",
            2L to "INSERT INTO z VALUES (1)\n$shared\nALTER TABLE q ADD r TEXT;",
            language = SimilarityLanguage.SQL,
        )
        assertEquals(12, SqlFrontend.minimumTokenMatch)
        assertNull(cohort("SELECT a, b FROM t WHERE c = 1;").pairOrNull(1L, 2L), "an 11-token run was matched")
        assertTrue(cohort("SELECT a, b, d FROM t WHERE c = 1;").pairOrNull(1L, 2L) != null, "a 13-token run was not")
    }

    @Test
    fun `fewer than two submissions is an empty result, not a failure`() {
        val outcome = compare(1L to original)
        assertTrue(outcome.pairs.isEmpty())
        assertTrue(outcome.skipped.isEmpty())
    }

    @Test
    fun `the rows the comparison ran on come back with it, including on the early exits`() {
        // The controller builds its response from these, and the page joins scores to them by id. An
        // earlier version had the loader assign them to a captured `var`, which was correct only
        // while every return path happened to run the loader first - so assert the rows survive the
        // paths that return without comparing anything, not just the happy one.
        val rows = listOf("a" to original, "b" to renamed)
        val run = service.compare(
            { rows },
            { (name, solution) -> SimilaritySource(name.first().code.toLong(), solution) },
            SimilarityLanguage.PYTHON, null, 100,
        )
        assertEquals(rows, run.rows, "the compared rows were not returned")
        assertTrue(run.outcome.pairs.isNotEmpty())

        val single = service.compare(
            { listOf("only" to original) },
            { (name, solution) -> SimilaritySource(name.first().code.toLong(), solution) },
            SimilarityLanguage.PYTHON, null, 100,
        )
        assertEquals(listOf("only" to original), single.rows, "the too-small-to-compare path lost the rows")
        assertTrue(single.outcome.pairs.isEmpty())
    }

    @Test
    fun `a student's own attempts are never reported against each other, however many there are`() {
        // The attack this closes: resubmitting is normal and encouraged, and every attempt scores
        // ~100% against the others. Fifteen of them produce 105 self-pairs, more than the top-N cap
        // returns, so the genuine copy is pushed out of the response entirely. Asserted with enough
        // attempts that they would dominate the ranking if they were kept.
        val mine = (1L..6L).map { SimilaritySource(it, original, owner = "student-a") }
        val theirs = listOf(SimilaritySource(99L, renamed, owner = "student-b"))
        val outcome = service.compare({ mine + theirs }, { it }, SimilarityLanguage.PYTHON, null, 100).outcome

        assertTrue(
            outcome.pairs.none { pair -> pair.id1 < 90 && pair.id2 < 90 },
            "a student was reported against their own attempts: ${outcome.pairs.map { it.id1 to it.id2 }}",
        )
        // And the real finding survives, once against each attempt rather than being crowded out.
        assertEquals(
            6, outcome.pairs.size,
            "expected one pair per attempt against the other student, got ${outcome.pairs.size}",
        )
        assertTrue(outcome.pairs.all { it.similarity > 0.9 })
    }

    @Test
    fun `an oversized solution is skipped rather than parsed, and does not cost the cohort`() {
        // Parsing is where a submission's cost is, and it goes superlinear, so this cap is what stops
        // one student holding the single permit. Asserted through the real limit rather than a mocked
        // one: a service whose cap is small enough to trip with a short string. The pair between the
        // other two is asserted as well, so a cap that happened to sit below their own size - skipping
        // the whole cohort and proving nothing, as a first version of this test did - fails here.
        val tight = service(maxSolutionTokens = 400)
        val huge = original + "\n" + "x = 1\n".repeat(300)

        val outcome = tight.compare(
            { listOf(SimilaritySource(1L, original), SimilaritySource(2L, renamed), SimilaritySource(3L, huge)) },
            { it }, SimilarityLanguage.PYTHON, null, 100,
        ).outcome

        assertEquals(listOf(3L), outcome.skipped.map { it.id })
        // TOO_LARGE, not CANNOT_PARSE: the code is fine, it is only too long, and telling a teacher
        // their student's code did not parse when it did would send them looking for the wrong thing.
        assertEquals("TOO_LARGE", outcome.skipped.single().reason)
        assertTrue(
            outcome.pair(1L, 2L).similarity > 0.9,
            "the usable pair was lost along with the oversized one",
        )
    }

    @Test
    fun `padding a copy with a long comment does not take it out of the comparison`() {
        // The cap used to count characters, and a comment is characters: 70 KB of it made a copy
        // TOO_LARGE, so it was never compared, while the code ran exactly as before. Comments never
        // reach the parser, and the cap is on what does.
        val padded = renamed + "\n# " + "not a copy ".repeat(7_000) + "\n"
        assertTrue(padded.length > 65_536, "the padding must beat the character cap it replaced")

        val outcome = compare(1L to original, 2L to padded)

        assertEquals(emptyList<SkippedSubmission>(), outcome.skipped, "the padded copy was dropped")
        assertTrue(outcome.pair(1L, 2L).similarity > 0.9, "the padded copy was not found: ${outcome.pairs}")
    }

    @Test
    fun `a solution repetitive enough to stall the tiling is skipped by name, ordinary repetition is not`() {
        // One pair of repetitive solutions cannot be interrupted once compared, and took minutes. The
        // bound is on each solution before any pair starts. 150 identical statements score about
        // 145² against a limit of 10,000 here; fifty prints score 45², and must still be compared.
        val bounded = service(maxRepetition = 10_000)
        val repetitive = renamed + "\n" + "x = 1\n".repeat(150)
        val somePrints = original + "\n" + "print(1)\n".repeat(50)

        val outcome = bounded.compare(
            {
                listOf(
                    SimilaritySource(1L, original), SimilaritySource(2L, renamed),
                    SimilaritySource(3L, repetitive), SimilaritySource(4L, somePrints),
                )
            },
            { it }, SimilarityLanguage.PYTHON, null, 100,
        ).outcome

        assertEquals(listOf(SkippedSubmission(3L, "TOO_REPETITIVE")), outcome.skipped)
        assertTrue(outcome.pair(1L, 2L).similarity > 0.9, "the usable pair was lost with the repetitive one")
        assertTrue(outcome.pair(1L, 4L).maxSimilarity > 0.9, "ordinary repetition was not compared")
    }

    // --- 3. base code, which used to take the whole request down ---

    @Test
    fun `a template shorter than the match length is dropped instead of failing the comparison`() {
        // SubmissionSet refuses a base code below the minimum match by throwing out of its constructor.
        // "# write your solution here" is one token, and is what a real exercise template looks like.
        val outcome = service.compare(
            { listOf(SimilaritySource(1L, original), SimilaritySource(2L, renamed)) },
            { it },
            SimilarityLanguage.PYTHON,
            "# write your solution here",
            100,
        ).outcome
        // Asserting the score: keeping a too-short template would make SubmissionSet subtract it and
        // return the pair at 0%, which a non-emptiness check cannot see.
        assertTrue(
            outcome.pair(1L, 2L).similarity > 0.9,
            "a short template changed the result instead of being ignored: ${outcome.pairs}",
        )
    }

    @Test
    fun `a template above the size cap, or too repetitive, is dropped rather than used`() {
        // Held to the limits a submission is: it is tokenised inside the single permit and tiled against
        // every submission, so an oversized or repetitive one would cost what such a submission does.
        // The template here is the solution itself, so were it kept it would be subtracted from both
        // sides and the pair would vanish; dropped, the pair is untouched.
        val cohort = { listOf(SimilaritySource(1L, original), SimilaritySource(2L, renamed)) }
        // Each past one limit only: the oversized one by length, the repetitive one - 150 identical
        // statements, about 145² against 10,000 - inside the default length cap.
        val pastALimit = listOf(
            service(maxSolutionTokens = 400) to original + "\n" + "y = 2\n".repeat(300),
            service(maxRepetition = 10_000) to original + "\n" + "x = 1\n".repeat(150),
        )
        for ((bounded, template) in pastALimit) {
            val outcome = bounded.compare(cohort, { it }, SimilarityLanguage.PYTHON, template, 100).outcome
            assertTrue(outcome.pair(1L, 2L).similarity > 0.9, "a template past a limit was still subtracted")
        }
    }

    @Test
    fun `shared template code is subtracted, so two solutions built on it are not flagged for it`() {
        val template = """
            def solve(values):
                total = 0
                for value in values:
                    total = total + value
                return total
        """.trimIndent()
        val withTemplate = { extra: String -> template + "\n" + extra }

        val sources = listOf(
            SimilaritySource(1L, withTemplate("print(solve([1, 2, 3]))")),
            SimilaritySource(2L, withTemplate("print(solve([4, 5, 6]))")),
        )
        val without =
            service.compare({ sources }, { it }, SimilarityLanguage.PYTHON, null, 100).outcome.pairs.single().similarity
        val with = service.compare({ sources }, { it }, SimilarityLanguage.PYTHON, template, 100).outcome.pairs

        // With the template subtracted there is almost nothing left that both students wrote, so the
        // pair either drops out entirely or scores below the un-subtracted figure. Without this, a
        // fill-in-the-blank exercise flags its whole cohort.
        val after = with.singleOrNull()?.similarity ?: 0.0
        assertTrue(after < without, "base code was not subtracted: $after is not below $without")
    }

    // --- 4. the text fallback ---

    @Test
    fun `text mode compares words and scores two identical answers at exactly one`() {
        val answer = "merge sort splits the array in half recursively until each piece has one element"
        val outcome = compare(1L to answer, 2L to answer, language = SimilarityLanguage.TEXT)

        // Exactly 1.0 rather than merely high: the divisor subtracts one token per file on the
        // assumption that the token list ends with FILE_END. Without it this reported 1.11.
        assertEquals(1.0, outcome.pair(1L, 2L).similarity, 1e-9)
        assertEquals(SimilarityLanguage.TEXT, outcome.language)
    }

    @Test
    fun `two text answers sharing their final words do not crash the comparison`() {
        // GreedyStringTiling advances with no bounds check and stops only on the excluded FILE_END
        // token. A text token list without one walks off the end of the array and takes the whole
        // comparison down - which is not an edge case, because essays share closing sentences.
        val outcome = compare(
            1L to "alpha beta gamma delta epsilon zeta eta theta iota kappa",
            2L to "prefix alpha beta gamma delta epsilon zeta eta theta iota kappa",
            language = SimilarityLanguage.TEXT,
        )
        assertTrue(
            outcome.pair(1L, 2L).similarity > 0.5,
            "the shared ending produced no usable score: ${outcome.pairs}",
        )
    }

    @Test
    fun `a language that cannot be tokenised in this JVM is compared as text, and the outcome says so`() {
        // Java's frontend is javac, which a minimal JRE leaves out. A word-level answer, named as one,
        // beats refusing - and beats every Java submission reading as CANNOT_PARSE, which is what
        // asking a frontend that cannot run would produce.
        val noJavac = service().also { it.available = { language -> language != SimilarityLanguage.JAVA } }
        val java = "class A { int twice(int x) { return x * 2; } void main() { System.out.println(twice(21)); } }"

        val outcome = compare(1L to java, 2L to java, language = SimilarityLanguage.JAVA, on = noJavac)

        assertEquals(SimilarityLanguage.TEXT, outcome.language)
        assertEquals(1.0, outcome.pair(1L, 2L).similarity, 1e-9)
        assertEquals(SimilarityLanguage.JAVA, compare(1L to java, 2L to java, language = SimilarityLanguage.JAVA).language)
    }

    @Test
    fun `each language's shortest match is the calibrated one`() {
        // Each is calibrated against a sweep (doc/jplag-integration.md), and no behaviour test here sits
        // close enough to Java's or text's edge to notice it moving: 8 to 9 changed no assertion. Move one
        // on purpose, with the sweep redone.
        assertEquals(
            mapOf(SimilarityLanguage.PYTHON to 6, SimilarityLanguage.JAVA to 8, SimilarityLanguage.SQL to 12, SimilarityLanguage.TEXT to 6),
            SimilarityLanguage.entries.associateWith { it.frontend.minimumTokenMatch },
        )
    }

    @Test
    fun `a frontend's exception is that one solution's parse failure, and an Error wrapped in one is not`() {
        // The step every real frontend's failures go through, which the hooks above step round: driven
        // here with failures no real input produces reliably.
        val failure = assertThrows<SimilarityParseException> { guarded(File("s")) { throw IllegalStateException("the listener choked") } }
        assertTrue(failure.cause is IllegalStateException)
        assertThrows<SolutionTooComplex> { guarded(File("s")) { throw SolutionTooComplex("too deep") } }
        // javac reports an Error thrown inside its parser as an IllegalStateException around it.
        val overflow = runCatching { guarded(File("s")) { throw IllegalStateException("wrapped", StackOverflowError()) } }.exceptionOrNull()
        assertTrue(overflow is StackOverflowError, "the Error surfaced as $overflow")
    }

    @Test
    fun `a cohort that is mostly not in the exercise's language is compared as text, and the outcome says so`() {
        // Every new exercise starts as `lahendus.py`, and a teacher-graded one has no grader image to
        // say otherwise, so an essay exercise's answers reached the Python parser. Error recovery made
        // each line of prose one identical token: unrelated essays paired at 90-100%, short answers were
        // TOO_SMALL, and the page said "python".
        val outcome = compare(1L to essays[0], 2L to essays[1], 3L to essays[2], 4L to essays[0])

        assertEquals(SimilarityLanguage.TEXT, outcome.language)
        assertEquals(1.0, outcome.pair(1L, 4L).similarity, 1e-9)
        for ((a, b) in listOf(1L to 2L, 1L to 3L, 2L to 3L)) {
            assertTrue((outcome.pairOrNull(a, b)?.maxSimilarity ?: 0.0) < 0.3, "unrelated essays $a and $b: ${outcome.pairOrNull(a, b)}")
        }
    }

    @Test
    fun `a cohort not in its language stays in it where an admin has switched text off`() {
        // Switched to text regardless, the check compared word by word where the admin had said no, and
        // edit distance - which the registry would have chosen - was never asked.
        val noText = SimilarityBinding(JPlagProvider.ID, SimilarityLanguage.PYTHON, textAllowed = false)
        val outcome = service.compare(
            { listOf(1L to essays[0], 2L to essays[1], 3L to essays[2], 4L to essays[0]).map { SimilaritySource(it.first, it.second) } },
            { it }, noText, null, 100,
        ).outcome
        assertEquals(SimilarityLanguage.PYTHON, outcome.language)
    }

    /** Prose in Estonian, as an essay exercise that kept `lahendus.py` collects it. */
    private val essays = listOf(
        "Minu lemmik aastaaeg on suvi, sest siis on soe ja saab ujumas käia.\nSuvel käin ma tihti vanaema juures maal.\n" +
            "Seal on palju loomi ja suur aed.\nÕhtuti loeme koos raamatuid ja räägime juttu.\n" +
            "Sügisel algab kool jälle ning siis on vähem aega.\nTalvel sajab lund ja me suusatame metsas.\n",
        "Programmeerimine on minu arvates huvitav, kuid vahel ka raske.\nKõige rohkem meeldib mulle ülesandeid lahendada koos sõpradega.\n" +
            "Eksamiks valmistumine võtab palju aega ja kannatust.\nLoodan, et saan sel aastal hea hinde.\n" +
            "Järgmisel semestril tahan õppida andmebaase.\nSiis oskan ehk ise mõne rakenduse teha.\n",
        "Eile käisime klassiga muuseumis, kus näitati vanu masinaid.\nGiid rääkis, kuidas esimesed arvutid olid terve toa suurused.\n" +
            "Mulle jäi meelde, et need kasutasid lampe ja releesid.\nPärast seda sõime linnas lõunat ja jalutasime vanalinnas.\n" +
            "Koju jõudsime alles õhtul, väsinud aga rõõmsad.\nJärgmine kord tahaksin minna teadusmuuseumisse.\n",
    )

    @Test
    fun `a cohort with a broken solution or two is still compared in its own language`() {
        // The fallback is for a cohort that is not code at all. A syntax error in one solution is the
        // ordinary case, recovered from and compared as code.
        val broken = original.replace("for word in words:", "for word in words")
        val outcome = compare(1L to original, 3L to unrelated, 4L to broken)

        assertEquals(SimilarityLanguage.PYTHON, outcome.language)
        assertTrue(outcome.pair(1L, 4L).maxSimilarity > 0.9, "the broken copy scored ${outcome.pair(1L, 4L)}")
    }

    @Test
    fun `a short-answer cohort read as Python is compared as text, though each answer is too small for Python`() {
        val answers = listOf(
            "Minu arvates on kõige tähtsam lugeda ülesanne enne lahendamist hoolikalt läbi.",
            "Tsükkel kordab sama tegevust seni, kuni tingimus kehtib ja siis lõpetab.",
            "Funktsioon aitab sama koodi mitu korda kasutada ilma seda uuesti kirjutamata.",
        )
        val outcome = compare(1L to answers[0], 2L to answers[1], 3L to answers[2], 4L to answers[0])

        assertEquals(SimilarityLanguage.TEXT, outcome.language)
        assertEquals(1.0, outcome.pair(1L, 4L).similarity, 1e-9)
    }

    @Test
    fun `a cohort that all uses one construct the grammar does not know is still compared as code`() {
        // A walrus read loop is valid Python and a syntax error to JPlag's 2016 grammar. When any error
        // made a solution count as not Python, a cohort that all read input this way was compared as
        // text, and the renamed copy - sharing no words with its original - was not reported.
        val loop = "\nwhile (line := input()) != \"\":\n    print(line)\n"
        val outcome = compare(1L to original + loop, 2L to renamed + loop, 3L to unrelated + loop)

        assertEquals(SimilarityLanguage.PYTHON, outcome.language)
        assertTrue(outcome.pair(1L, 2L).maxSimilarity > 0.9, "the renamed copy scored ${outcome.pairOrNull(1L, 2L)}")
    }

    @Test
    fun `a pair that would display as 0% is dropped, not only one that scored exactly zero`() {
        // One shared six-word phrase between two 1,500-word essays is a single 6-token match: a
        // maximum of 0.4%, which the page rounds to 0% — the row the zero filter exists to keep off
        // the list, and one that would still have counted toward `truncated`.
        val phrase = "on the other hand it is"
        val first = (1..1500).joinToString(" ") { "alpha$it" } + " " + phrase
        val second = phrase + " " + (1..1500).joinToString(" ") { "beta$it" }
        val outcome = compare(1L to first, 2L to second, language = SimilarityLanguage.TEXT)

        assertNull(outcome.pairOrNull(1L, 2L), "a pair that rounds to 0% was reported: ${outcome.pairs}")
        // With a cap of zero pairs, `truncated` says whether any pair scored at all, so this can fail:
        // at the default cap one pair never exceeds it, whatever the filter does.
        val capped = compare(1L to first, 2L to second, language = SimilarityLanguage.TEXT, topN = 0)
        assertFalse(capped.truncated, "a pair that rounds to 0% still counted toward truncated")
    }

    // --- 5. the bounds, the permit, and running in parallel ---

    @Test
    fun `the cap refuses a count above it and admits one at it`() {
        // That it runs *before* any solution is read is the controller's to keep, and SimilarityApiTest
        // pins the order: `the cap is checked on the named ids, before any of them is looked up`.
        val tooMany = assertThrows<TooManySubmissions> { service.assertWithinLimit(51) }
        assertEquals(51, tooMany.requested)
        assertEquals(50, tooMany.limit)

        service.assertWithinLimit(50)
    }

    @Test
    fun `a run past its budget is refused as timed out, and the permit is released`() {
        // A zero budget is spent before the first task starts, so every task finds it gone.
        val noBudget = service(timeoutSec = 0)
        val sources = { listOf(SimilaritySource(1L, original), SimilaritySource(2L, renamed)) }

        val refused = assertThrows<SimilarityTimedOut> {
            noBudget.compare(sources, { it }, SimilarityLanguage.PYTHON, null, 100)
        }
        assertEquals(0L, refused.budgetSec)
        // Refused for the budget again, rather than left waiting 30 s and refused as busy: the
        // permit came back although the first run threw.
        assertThrows<SimilarityTimedOut> { noBudget.compare(sources, { it }, SimilarityLanguage.PYTHON, null, 100) }
    }

    @Test
    fun `a budget too large to add to the clock means no limit, not an instant refusal`() {
        // Long.MAX_VALUE seconds saturates to Long.MAX_VALUE nanoseconds, and adding that to the clock
        // wraps. Compared as `now > deadline`, every run was refused at once; as a difference, never.
        val unlimited = service(timeoutSec = Long.MAX_VALUE)
        assertTrue(compare(1L to original, 2L to renamed, on = unlimited).pair(1L, 2L).similarity > 0.9)
    }

    @Test
    fun `the permit is released when the loader throws`() {
        // The loader runs inside the permit - it is the database read of the cohort - so a failure
        // there is a failure inside it. Released, the next caller runs; leaked, it waits 30 s and is
        // refused as busy, and so is every caller after it, until a restart.
        assertThrows<IllegalStateException> {
            service.compare({ error("database gone") }, { it: SimilaritySource -> it }, SimilarityLanguage.PYTHON, null, 100)
        }
        assertTrue(compare(1L to original, 2L to renamed).pair(1L, 2L).similarity > 0.9)
    }

    @Test
    fun `the budget stops a comparison phase that outlasts it, between pairs`() {
        // The comparison used to be JPlag's driver, which waits up to 24 hours and could not be
        // stopped once started, so the budget covered tokenising only. Pairs are made slow here - a
        // really slow pair is what maxRepetition now refuses to run - and 435 of them at 25 ms each
        // would take seconds even spread over the pool. The budget is one second.
        val clock = ManualTicker()
        val compared = AtomicInteger()
        val slow = service(timeoutSec = 1, clock = clock)
        slow.comparePair = { gst, first, second -> compared.incrementAndGet(); clock.advance(25); gst.compare(first, second) }
        slow.threadsFor = { 2 }
        // The deadline itself, behind the pace that would otherwise refuse this run first.
        slow.projectRuns = false
        // A tail of its own length each: identical submissions would be one group, compared once.
        val sources = (1L..30L).map { SimilaritySource(it, original + "\n" + "print($it)\n".repeat(it.toInt())) }

        assertThrows<SimilarityTimedOut> { slow.compare({ sources }, { it }, SimilarityLanguage.PYTHON, null, 100) }
        // A second at 25 ms a pair is 40 pairs, and each worker may finish the one it began: not all 435.
        assertTrue(compared.get() <= 42, "compared ${compared.get()} pairs, so the pairs past the budget were still compared")
    }

    @Test
    fun `a comparison whose pace says it cannot finish is refused as too costly before the deadline`() {
        // The same slow pairs, with the pace on: 435 pairs at 25 ms on two threads is over 5 s against a
        // budget of one, which a tenth of it shows; refused then, not when the second is spent.
        val clock = ManualTicker()
        val slow = service(timeoutSec = 1, clock = clock)
        slow.comparePair = { gst, first, second -> clock.advance(25); gst.compare(first, second) }
        slow.threadsFor = { 2 }
        val sources = (1L..30L).map { SimilaritySource(it, original + "\n" + "print($it)\n".repeat(it.toInt())) }

        val refused = assertThrows<SimilarityTooCostly> { slow.compare({ sources }, { it }, SimilarityLanguage.PYTHON, null, 100) }
        assertEquals(435, refused.estimate.pairs)
        assertTrue(refused.estimate.seconds > 1, "projected only ${refused.estimate.seconds}s")
    }

    @Test
    fun `the budget is read before each solution is tokenised, so a spent one costs nothing more`() {
        // Deleting either check on the tokenising side - before each solution, or after them all -
        // left every other budget test green, because the first pair's check threw the same refusal.
        // Without them a spent budget still tokenised the whole cohort, holding the one permit.
        val tokenised = AtomicInteger()
        val compared = AtomicInteger()
        val clock = ManualTicker()
        val slow = service(timeoutSec = 1, clock = clock).also {
            it.threadsFor = { 1 }
            it.tokenise = { language, id, solution, maxTokens ->
                tokenised.incrementAndGet()
                clock.advance(50)
                language.read(id, solution, maxTokens)
            }
            it.comparePair = { gst, first, second -> compared.incrementAndGet(); gst.compare(first, second) }
            // The deadline itself, behind the pace that would otherwise refuse this run first.
            it.projectRuns = false
        }
        // A tail of its own length each: identical, they were one group, and no pair was compared anyway.
        val sources = (1L..100L).map { SimilaritySource(it, original + "\n" + "print($it)\n".repeat(it.toInt())) }

        assertThrows<SimilarityTimedOut> { slow.compare({ sources }, { it }, SimilarityLanguage.PYTHON, null, 100) }
        assertTrue(tokenised.get() <= 21, "tokenised ${tokenised.get()} of 100 solutions on a one-second budget of 50 ms each")
        assertEquals(0, compared.get(), "a pair was compared although the budget was spent while tokenising")
    }

    @Test
    fun `tokenising whose pace says it cannot finish is refused as too costly before the deadline`() {
        // Tokenising has nothing to show part way, so a cohort it would spend the budget on held the one
        // permit to the deadline and then timed out. 100 solutions at 50 ms of the test's clock on one
        // thread is 5 s against a budget of one: a tenth of it shows that, and the rest is never read.
        val tokenised = AtomicInteger()
        val clock = ManualTicker()
        val slow = service(timeoutSec = 1, clock = clock).also {
            it.threadsFor = { 1 }
            it.tokenise = { language, id, solution, maxTokens ->
                tokenised.incrementAndGet()
                clock.advance(50)
                language.read(id, solution, maxTokens)
            }
        }
        val sources = (1L..100L).map { SimilaritySource(it, original + "\n" + "print($it)\n".repeat(it.toInt())) }

        val refused = assertThrows<SimilarityTooCostly> { slow.compare({ sources }, { it }, SimilarityLanguage.PYTHON, null, 100) }
        assertTrue(refused.estimate.seconds >= 5, "projected only ${refused.estimate.seconds}s")
        assertTrue(tokenised.get() <= 3, "tokenised ${tokenised.get()} of 100 solutions before the pace was read")
    }

    @Test
    fun `tokenising again as text is paced afresh, against the time then left`() {
        // A cohort that is not in its language is read twice: a sample in it, then everything as text. The
        // second pass carried on the first's count, so twenty cheap reads made a slow text pass look a
        // fifth done when its pace was read, and it ran to the deadline. Here reading as Python costs
        // nothing and as text 50 ms, so the text pass alone is 5 s against a budget of one.
        val asText = AtomicInteger()
        val clock = ManualTicker()
        val slow = service(timeoutSec = 1, clock = clock).also {
            it.threadsFor = { 1 }
            it.tokenise = { language, id, solution, maxTokens ->
                if (language == SimilarityLanguage.TEXT) {
                    asText.incrementAndGet()
                    clock.advance(50)
                }
                language.read(id, solution, maxTokens)
            }
        }
        val sources = (1L..100L).map { SimilaritySource(it, essays[it.toInt() % essays.size] + "\nLõpetuseks veel mõte number $it.") }

        assertThrows<SimilarityTooCostly> { slow.compare({ sources }, { it }, SimilarityLanguage.PYTHON, null, 100) }
        assertTrue(asText.get() <= 3, "read ${asText.get()} of 100 solutions as text before the pace was read")
    }

    @Test
    fun `the tokenising pace counts what each solution costs, so large ones first do not make a cohort that fits look long`() {
        // Five large solutions and then 95 small ones, each taking what the cost model says it does: 3 s in
        // all against a budget of ten. Counted by solutions, the pace read after the five large ones - a tenth
        // of the budget - took them for a twentieth of the work and refused the cohort as taking 22 s.
        val clock = ManualTicker()
        val model = JPlagProvider(10_000, 1_000_000)
        val slow = service(timeoutSec = 10, clock = clock).also {
            it.threadsFor = { 1 }
            it.tokenise = { language, id, solution, maxTokens ->
                clock.advance(model.prepareNanos(utf8Length(solution)) / 1_000_000)
                language.read(id, solution, maxTokens)
            }
        }
        val large = "x = 1\n".repeat(17_000)
        val sources = (1L..100L).map { SimilaritySource(it, (if (it <= 5) large else original + "\n") + "print($it)\n") }

        slow.compare({ sources }, { it }, SimilarityLanguage.PYTHON, null, 100)
    }

    @Test
    fun `the budget is read again once every solution is tokenised`() {
        // The last solution starts inside the budget and ends past it. Nothing is left to compare - the
        // other is too small - so without the check after tokenising, the run past its budget answered
        // as if it had finished in time: no pair check was ever reached to refuse it.
        val clock = ManualTicker()
        val slow = service(timeoutSec = 1, clock = clock).also {
            it.threadsFor = { 1 }
            it.tokenise = { language, id, solution, maxTokens ->
                if (solution == original) clock.advance(1_200)
                language.read(id, solution, maxTokens)
            }
            it.projectRuns = false
        }
        val sources = listOf(SimilaritySource(1, ""), SimilaritySource(2, original))

        assertThrows<SimilarityTimedOut> { slow.compare({ sources }, { it }, SimilarityLanguage.PYTHON, null, 100) }
    }

    @Test
    fun `one failure stops the rest, and nothing is still running once compare has thrown`() {
        // Two properties the permit depends on. The work of a failed run must stop - the rest of the
        // cohort not tokenised under the same pressure that failed one - and must be over when the
        // caller is told, or the permit passes to the next run while this one's last tasks still
        // hold CPU and heap. Each task here is a busy wait, which, like a parse, ignores interrupts.
        val started = AtomicInteger()
        val finished = AtomicInteger()
        val failingFast = service().also { failing ->
            failing.threadsFor = { 2 }
            failing.tokenise = { language, id, solution, maxTokens ->
                started.incrementAndGet()
                if (solution == CHOKES) throw IllegalStateException("fails at once")
                val until = System.nanoTime() + 300_000_000L
                while (System.nanoTime() - until < 0) Thread.onSpinWait()
                finished.incrementAndGet()
                language.read(id, solution, maxTokens)
            }
        }
        val sources = listOf(SimilaritySource(1L, CHOKES)) + (2L..40L).map { SimilaritySource(it, original) }

        assertThrows<IllegalStateException> { failingFast.compare({ sources }, { it }, SimilarityLanguage.PYTHON, null, 100) }

        assertEquals(started.get() - 1, finished.get(), "a task was still running when compare threw")
        assertTrue(started.get() <= 3, "${started.get()} of ${sources.size} were tokenised after the failure")
    }

    @Test
    fun `a pair that fails stops the other workers at their next pair`() {
        // The ranking's stripes run side by side for most of a run. One failing - a bug in a JPlag
        // upgrade, an OutOfMemoryError - must stop the others, not leave them comparing every pair they
        // have left before the failure is reported. Each pair here takes 5 ms, like a real slow one.
        val compared = AtomicInteger()
        val failing = service().also { failing ->
            failing.threadsFor = { 2 }
            failing.comparePair = { gst, first, second ->
                if (compared.incrementAndGet() == 10) throw IllegalStateException("pair 10")
                val until = System.nanoTime() + 5_000_000L
                while (System.nanoTime() - until < 0) Thread.onSpinWait()
                gst.compare(first, second)
            }
        }
        // A tail of its own length each: identical submissions would be one group, compared once.
        val sources = (1L..30L).map { SimilaritySource(it, original + "\n" + "print($it)\n".repeat(it.toInt())) }

        assertThrows<IllegalStateException> { failing.compare({ sources }, { it }, SimilarityLanguage.PYTHON, null, 100) }
        assertTrue(compared.get() < 20, "${compared.get()} of 435 pairs were compared, most after the failure")
    }

    @Test
    fun `a student's own pairs are not compared at all, not merely left out of the result`() {
        // Five attempts by one student and one by another: four pairs worth comparing, and ten that
        // are the student against themselves - which used to be compared too, and thrown away.
        val compared = AtomicInteger()
        val counting = service()
        counting.comparePair = { gst, first, second -> compared.incrementAndGet(); gst.compare(first, second) }
        val sources = (1L..4L).map { SimilaritySource(it, original, owner = "student-a") } +
                SimilaritySource(9L, renamed, owner = "student-b")

        val outcome = counting.compare({ sources }, { it }, SimilarityLanguage.PYTHON, null, 100).outcome

        assertEquals(4, compared.get(), "pairs compared")
        assertEquals(4, outcome.pairs.size)
    }

    /** Sixteen submissions built from shared fragments: most pairs score, many tie, and a cut at 12 falls among them. */
    private val tiedCohort: List<SimilaritySource> by lazy {
        val fragments = listOf(original, renamed, unrelated, "print(sum(range(10)))\nprint('done')\n",
            "n = int(input())\nfor i in range(n):\n    print(i * i)\n")
        (1L..16L).map { id ->
            val i = id.toInt()
            SimilaritySource(id, fragments[i % 5] + "\n" + fragments[(i * 3) % 5] + "\n" + "x$i = $i\n".repeat(i % 3))
        }
    }

    @Test
    fun `the pairs and their order are exactly what JPlag's own driver finds`() {
        // The loop replaced LongestCommonSubsequenceSearch and must not have changed its answer. So
        // the driver is run beside it over the same tokens, ranked by the same rule, cut at the same
        // N, and every pair, score and matched region compared.
        val sources = tiedCohort
        val topN = 12
        val minimumTokenMatch = PythonFrontend.minimumTokenMatch

        val actual = service.compare({ sources }, { it }, SimilarityLanguage.PYTHON, null, topN).outcome.pairs

        val tokens = sources.associate { source ->
            val file = File("submission-${source.id}")
            file to SimilarityLanguage.PYTHON.tokenise(file, source.solution, 10_000)
        }
        val language = object : Language {
            override fun fileExtensions() = listOf(".py")
            override fun getName() = "oracle"
            override fun getIdentifier() = "oracle"
            override fun minimumTokenMatch() = minimumTokenMatch
            override fun parse(files: Set<File>, normalize: Boolean): List<Token> =
                files.flatMap { tokens[it] ?: throw ParsingException(it, "missing") }
        }
        val options = JPlagOptions(language, emptySet(), emptySet()).withMinimumTokenMatch(minimumTokenMatch)
        val set = SubmissionSet(
            sources.map { File("submission-${it.id}").let { f -> Submission(it.id.toString(), f, true, listOf(f), language) } },
            null, options,
        )
        fun JPlagComparison.ids() = firstSubmission().name.toLong() to secondSubmission().name.toLong()
        fun lineAt(list: List<Token>, index: Int) = list.getOrNull(index)?.startLine?.takeIf { it > 0 } ?: 0
        fun JPlagComparison.shared() = matches().sumOf { it.minimumLength() }
        val byShared = compareByDescending<JPlagComparison> { it.shared() }
            .thenByDescending { it.maximalSimilarity() }
            .thenByDescending { it.similarity() }
            .thenBy { minOf(it.ids().first, it.ids().second) }
            .thenBy { maxOf(it.ids().first, it.ids().second) }
        val byPercent = compareByDescending<JPlagComparison> { it.maximalSimilarity() }
            .thenByDescending { it.similarity() }
            .thenByDescending { it.shared() }
            .thenBy { minOf(it.ids().first, it.ids().second) }
            .thenBy { maxOf(it.ids().first, it.ids().second) }
        val scoring = LongestCommonSubsequenceSearch(options).compareSubmissions(set).allComparisons
            .filter { it.maximalSimilarity() >= 0.005 }
        // The best N by what is shared and the best N by the percentage, together, in the first order.
        val expected = (scoring.sortedWith(byShared).take(topN) + scoring.sortedWith(byPercent).take(topN))
            .distinctBy { it.ids() }
            .sortedWith(byShared)
            .map { c ->
                val (id1, id2) = c.ids()
                val t1 = c.firstSubmission().tokenList
                val t2 = c.secondSubmission().tokenList
                SimilarityPair(id1, id2, c.similarity(), c.maximalSimilarity(), c.matches().map { m ->
                    SimilarityMatch(
                        lineAt(t1, m.startOfFirst()), lineAt(t1, m.startOfFirst() + m.lengthOfFirst() - 1),
                        lineAt(t2, m.startOfSecond()), lineAt(t2, m.startOfSecond() + m.lengthOfSecond() - 1),
                        m.minimumLength(),
                    )
                }, listOf(
                    SimilarityMetric("similarity", c.similarity(), MetricUnit.SHARE),
                    SimilarityMetric("max_similarity", c.maximalSimilarity(), MetricUnit.SHARE),
                    SimilarityMetric("shared_tokens", c.shared().toDouble(), MetricUnit.COUNT),
                ))
            }

        assertTrue(scoring.size > expected.size, "the cohort is meant to have more scoring pairs than the cut")
        assertTrue(expected.size > topN, "the two orders are meant to keep different pairs here")
        assertEquals(expected, actual)
    }

    /** Starter code, and solutions that grew from it by filling in its one function. */
    private val starter = "def average(numbers):\n    return 0\n\ndef main():\n    numbers = []\n    line = input()\n" +
        "    while line != \"\":\n        numbers.append(int(line))\n        line = input()\n    print(average(numbers))\n\nmain()\n"

    private fun grown(body: String) = starter.replace("    return 0\n", body)

    private val grownBodies = listOf(
        "    return sum(numbers) / len(numbers)\n",
        "    total = 0\n    for n in numbers:\n        total += n\n    return total / len(numbers)\n",
        "    if not numbers:\n        return 0\n    return sum(numbers) / len(numbers)\n",
        "    s = 0\n    c = 0\n    for x in numbers:\n        s = s + x\n        c = c + 1\n    return s / c\n",
    )

    @Test
    fun `pairs are ranked by how much code they share, so a stub inside every solution does not outrank a copy`() {
        // Ranked by the higher percentage alone, the starter code handed in unchanged is 100% of itself
        // inside every solution that grew from it, and two such stubs 100% on both scores: all of those
        // outranked a real copy that was not also 100% - with twelve of them, a copy fell from first to
        // 67th. The copy here swaps one statement for one of another kind, so neither is covered whole.
        val original = grown("    best = None\n    total = 0\n    for value in numbers:\n        if best is None or value > best:\n" +
            "            best = value\n        total += value\n    print('max', best)\n    return round(total / len(numbers), 2)\n")
        val copy = original.replace("best", "suurim").replace("value", "v").replace("    print('max', suurim)\n", "    assert suurim is not None\n")
        val sources = listOf(SimilaritySource(1, original, "a"), SimilaritySource(2, copy, "b")) +
            grownBodies.mapIndexed { i, body -> SimilaritySource(3L + i, grown(body), "h$i") } +
            listOf(SimilaritySource(20, starter, "s1"), SimilaritySource(21, starter, "s2"))

        val outcome = service.compare({ sources }, { it }, SimilarityLanguage.PYTHON, null, 100).outcome

        val copyPair = outcome.pair(1L, 2L)
        assertTrue(copyPair.maxSimilarity < 1.0, "the copy is meant to be under 100%: $copyPair")
        assertTrue(outcome.pair(20L, 21L).maxSimilarity == 1.0, "the stubs are meant to be 100% alike")
        assertEquals(setOf(1L, 2L), outcome.pairs.first().let { setOf(it.id1, it.id2) }, "ranked first: ${outcome.pairs.first()}")
    }

    @Test
    fun `the highest-scoring pairs are kept beside the ones sharing the most, so the threshold has them to filter`() {
        // Cut by what is shared alone, a short copy at 100% sharing a few tokens fell below long pairs
        // sharing more at a lower percentage, and a teacher filtering on 90% was shown nothing. Each long
        // solution here is the same function and a solution of its own; the short pair is a renamed copy.
        val shortCopy = "def square(n):\n    result = n * n\n    return result\n\nprint(square(int(input())))\n"
        val sources = grownBodies.mapIndexed { i, body -> SimilaritySource(1L + i, original + "\n\n" + grown(body), "long$i") } +
            listOf(SimilaritySource(10, shortCopy, "a"), SimilaritySource(11, shortCopy.replace("result", "r").replace(" n", " x").replace("(n", "(x"), "b"))

        val outcome = service.compare({ sources }, { it }, SimilarityLanguage.PYTHON, null, 1).outcome

        val short = outcome.pairOrNull(10L, 11L)
        assertTrue(short != null && short.maxSimilarity == 1.0, "the 100% pair was cut: ${outcome.pairs}")
        assertTrue(setOf(outcome.pairs.first().id1, outcome.pairs.first().id2).all { it < 10 }, "ordered by what is shared: ${outcome.pairs}")
        assertTrue(outcome.truncated)
    }

    @Test
    fun `identical submissions by three or more authors are one group, compared once`() {
        // Four students hand in the starter code unchanged: six pairs at 100% among themselves and four
        // against every other student, where one line says it all. Each other submission is compared with
        // the group once, and the pair names one member for it.
        val stubs = (10L..13L).map { SimilaritySource(it, starter, "stub$it") }
        val solutions = grownBodies.mapIndexed { i, body -> SimilaritySource(1L + i, grown(body), "author$i") }

        val outcome = service.compare({ solutions + stubs }, { it }, SimilarityLanguage.PYTHON, null, 100).outcome

        assertEquals(listOf(listOf(10L, 11L, 12L, 13L)), outcome.groups)
        val stubIds = stubs.map { it.id }.toSet()
        assertTrue(outcome.pairs.none { it.id1 in stubIds && it.id2 in stubIds }, "a pair inside the group: ${outcome.pairs}")
        for (solution in solutions) {
            val withGroup = outcome.pairs.filter { pair -> solution.id in setOf(pair.id1, pair.id2) && setOf(pair.id1, pair.id2).any { it in stubIds } }
            assertEquals(1, withGroup.size, "submission ${solution.id} against the group: $withGroup")
        }
    }

    @Test
    fun `a solution shorter than the shortest match is skipped as too small, not compared or grouped`() {
        // Eleven SQL tokens to a minimum match of twelve: JPlag's TOO_SMALL counts FILE_END, so this was
        // valid and could never match - two copies were no pair and no skip, three an "identical" group.
        val query = "SELECT nimi, vanus FROM isik WHERE id = 5;"
        val outcome = compare(1L to query, 2L to query, 3L to query, language = SimilarityLanguage.SQL)

        assertEquals(listOf(1L, 2L, 3L).map { SkippedSubmission(it, "TOO_SMALL") }, outcome.skipped)
        assertEquals(emptyList<List<Long>>(), outcome.groups)
    }

    @Test
    fun `different programs that tokenise alike are pairs, not a group of identical code`() {
        // Python's tokens leave out operators and literals, so a maximum, a minimum and an at-least were
        // one group: reported as identical code and shown as the first one's alone.
        val program = { comparison: String ->
            "def f(xs):\n    m = xs[0]\n    for x in xs:\n        if x $comparison m:\n            m = x\n    return m\n\nprint(f([3, 1, 2]))\n"
        }
        val outcome = compare(1L to program(">"), 2L to program("<"), 3L to program(">="))

        assertEquals(emptyList<List<Long>>(), outcome.groups)
        assertEquals(3, outcome.pairs.size)
    }

    @Test
    fun `a match that ends on a string spanning lines ends on the string's last line`() {
        val shared = "SELECT a, b, c, d, e FROM t WHERE x = 'one\ntwo\nthree'"
        val outcome = compare(1L to "$shared;", 2L to "$shared AND y;", language = SimilarityLanguage.SQL)

        val pair = outcome.pair(1L, 2L)
        assertEquals(3, pair.matches.maxOf { it.endLine1 }, "${pair.matches}")
        assertEquals(3, pair.matches.maxOf { it.endLine2 }, "${pair.matches}")
    }

    @Test
    fun `identical submissions by fewer than three authors are pairs as before, and one author's attempts are no group`() {
        val twoAuthors = service.compare(
            { listOf(SimilaritySource(1, starter, "a"), SimilaritySource(2, starter, "b"), SimilaritySource(3, grown(grownBodies[0]), "c")) },
            { it }, SimilarityLanguage.PYTHON, null, 100,
        ).outcome
        assertEquals(emptyList<List<Long>>(), twoAuthors.groups)
        assertEquals(1.0, twoAuthors.pair(1L, 2L).similarity, 1e-9)

        val oneAuthor = service.compare(
            { (1L..3L).map { SimilaritySource(it, starter, "same") } + SimilaritySource(4, grown(grownBodies[0]), "other") },
            { it }, SimilarityLanguage.PYTHON, null, 100,
        ).outcome
        assertEquals(emptyList<List<Long>>(), oneAuthor.groups)
    }

    @Test
    fun `a group is compared with its first member's own other attempt through another member`() {
        // Explicit ids can bring one student's two attempts: one inside a group, one outside it. The
        // group's first member is that student, and their own attempts are never paired - but the group
        // holds other authors, and the pair must name one of them, with lines read from its tokens.
        val laid = starter.replace("\n\ndef main", "\n\n\n\ndef main")
        val sources = listOf(
            SimilaritySource(1, starter, "ann"), SimilaritySource(2, laid, "ben"), SimilaritySource(3, starter, "cid"),
            SimilaritySource(4, grown(grownBodies[1]), "ann"),
        )

        val outcome = service.compare({ sources }, { it }, SimilarityLanguage.PYTHON, null, 100).outcome

        assertEquals(listOf(listOf(1L, 2L, 3L)), outcome.groups)
        val pair = outcome.pairs.single()
        assertEquals(setOf(2L, 4L), setOf(pair.id1, pair.id2), "the group should be shown by its first member of another author")
        // Ben's copy has two more blank lines, so the shared tail ends on his line 14 where Ann's ends on 12.
        val benEnd = pair.matches.maxOf { if (pair.id1 == 2L) it.endLine1 else it.endLine2 }
        assertEquals(laid.trimEnd().lines().size, benEnd, "lines read from another member's tokens: ${pair.matches}")
    }

    @Test
    fun `the answer is the same on any number of threads`() {
        // The pairs are split across workers, each keeps its own best, and the lists are merged: all of
        // which is correct only if no tie is left for the split to decide. Run on the default count
        // alone, the merge was exercised on one number of threads - whatever the machine had.
        val outcomes = listOf(1, 2, 3, 5, 16).map { threads ->
            service().also { it.threadsFor = { threads } }.compare({ tiedCohort }, { it }, SimilarityLanguage.PYTHON, null, 12).outcome
        }
        outcomes.drop(1).forEach { assertEquals(outcomes.first(), it) }
        assertTrue(outcomes.first().truncated, "the cut is meant to fall among ties")
    }

    @Test
    fun `one caller may wait for a running comparison, and a second is refused at once`() {
        // Each waiter parks a Tomcat thread for up to 30 s, and with no bound some 200 parallel
        // requests parked all of them. The running comparison here is a loader held open by a latch.
        val shared = service()
        val loaderEntered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val none = { emptyList<SimilaritySource>() }
        val running = thread {
            shared.compare({ loaderEntered.countDown(); finish.await(); emptyList<SimilaritySource>() }, { it },
                SimilarityLanguage.PYTHON, null, 100)
        }
        loaderEntered.await()
        var waited: Result<SimilarityRun<SimilaritySource>>? = null
        val waiter = thread { waited = runCatching { shared.compare(none, { it }, SimilarityLanguage.PYTHON, null, 100) } }
        // Parked on the permit: tryAcquire with a timeout is a timed wait.
        val parkedBy = System.nanoTime() + 5_000_000_000L
        while (waiter.state != Thread.State.TIMED_WAITING && System.nanoTime() < parkedBy) Thread.sleep(10)
        assertEquals(Thread.State.TIMED_WAITING, waiter.state, "the first caller never started waiting")

        val started = System.nanoTime()
        assertThrows<SimilarityBusy> { shared.compare(none, { it }, SimilarityLanguage.PYTHON, null, 100) }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(tookMs < 5_000, "the second caller waited ${tookMs}ms instead of being refused at once")

        finish.countDown()
        running.join()
        waiter.join()
        assertTrue(waited!!.isSuccess, "the caller allowed to wait was not given the permit: $waited")
    }

    @Test
    fun `a caller interrupted while waiting for the permit is refused as busy, and keeps its interrupt`() {
        // A server shutting down interrupts its request threads. InterruptedException used to reach the
        // generic handler as a 500 with an ops mail, and lose the interrupt on the way.
        val shared = service()
        val loaderEntered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val running = thread {
            shared.compare({ loaderEntered.countDown(); finish.await(); emptyList<SimilaritySource>() }, { it },
                SimilarityLanguage.PYTHON, null, 100)
        }
        loaderEntered.await()
        var outcome: Throwable? = null
        var stillInterrupted = false
        val waiter = thread {
            outcome = runCatching { shared.compare({ emptyList<SimilaritySource>() }, { it }, SimilarityLanguage.PYTHON, null, 100) }
                .exceptionOrNull()
            stillInterrupted = Thread.currentThread().isInterrupted
        }
        val parkedBy = System.nanoTime() + 5_000_000_000L
        while (waiter.state != Thread.State.TIMED_WAITING && System.nanoTime() < parkedBy) Thread.sleep(10)
        waiter.interrupt()
        waiter.join()
        finish.countDown()
        running.join()

        assertTrue(outcome is SimilarityBusy, "an interrupted waiter got $outcome")
        assertTrue(stillInterrupted, "the interrupt was swallowed")
        // Nobody holds the permit or counts as waiting now: a plain call runs.
        assertTrue(compare(1L to original, 2L to renamed, on = shared).pair(1L, 2L).similarity > 0.9)
    }

    @Test
    fun `matched regions are reported as line numbers inside both submissions`() {
        // The second submission is the first one renamed, below four lines of its own, so the same
        // match sits on different lines in each and swapping the two sides changes the answer. With
        // two files of identical layout the assertions here stayed green against swapped token lists.
        val prefix = "import sys\n\nprint(sys.argv)\n\n"
        val pair = compare(1L to original, 2L to prefix + renamed).pair(1L, 2L)
        val match = pair.matches.single()
        assertTrue(match.tokens > 0, "a match covering no tokens")

        // Read by the id each side belongs to, not by position: JPlag decides which one is first.
        val sides = listOf(match.startLine1..match.endLine1, match.startLine2..match.endLine2)
        val (lines1, lines2) = if (pair.id1 == 1L) sides[0] to sides[1] else sides[1] to sides[0]
        val lineCount = original.lines().size
        assertEquals(1..lineCount, lines1, "submission 1's matched lines")
        assertEquals(5..(4 + lineCount), lines2, "submission 2's matched lines")
    }

    @Test
    fun `a match ending on a one-line block ends on that line, not the next`() {
        // A one-line body ends in a NEWLINE, not a DEDENT, and JPlag's lexer stamps NEWLINE with the
        // line after it — so the band ran one line past the match and painted a line neither
        // student shares. Multi-line bodies end in a DEDENT, which JPlag already corrects, which is
        // why no other fixture here could see it.
        val first = "n = int(input())\nfor i in range(n): print(i * i)\nprint('done')"
        val second = "k = int(input())\nfor j in range(k): print(j * j)\ntotal = 0"
        val match = compare(1L to first, 2L to second).pair(1L, 2L).matches.single()

        assertEquals(1..2, match.startLine1..match.endLine1, "side 1 ran past the one-line loop")
        assertEquals(1..2, match.startLine2..match.endLine2, "side 2 ran past the one-line loop")
    }

    @Test
    fun `a one-line block ending in a multi-line string ends on the string's last line`() {
        // The statement ends where the string does, so the band must cover its last line too; put on
        // the line the string starts, the rest of the matched statement was left unshaded.
        val first = "n = int(input())\nm = int(input())\nif n > m: msg = \"\"\"big\ndifference\"\"\"\nprint(n)"
        val second = "a = int(input())\nb = int(input())\nif a > b: txt = \"\"\"big\ndifference\"\"\"\ntotal = 0"
        val match = compare(1L to first, 2L to second).pair(1L, 2L).matches.single()

        assertEquals(1..4, match.startLine1..match.endLine1, "side 1 stopped on the string's first line")
        assertEquals(1..4, match.startLine2..match.endLine2, "side 2 stopped on the string's first line")
    }

    // --- 6. language selection ---

    @Test
    fun `the tokeniser is chosen from the exercise's solution file name`() {
        assertEquals(SimilarityLanguage.PYTHON, SimilarityLanguage.forFileName("solution.py"))
        assertEquals(SimilarityLanguage.PYTHON, SimilarityLanguage.forFileName("LAHENDUS.PY"))
        assertEquals(SimilarityLanguage.PYTHON, SimilarityLanguage.forFileName("main.pyw"))
        assertEquals(SimilarityLanguage.JAVA, SimilarityLanguage.forFileName("Main.java"))
        assertEquals(SimilarityLanguage.SQL, SimilarityLanguage.forFileName("lahendus.SQL"))
        // Anything we cannot parse as code still gets compared, as text.
        assertEquals(SimilarityLanguage.TEXT, SimilarityLanguage.forFileName("essay.txt"))
        assertEquals(SimilarityLanguage.TEXT, SimilarityLanguage.forFileName("main.js"))
        assertEquals(SimilarityLanguage.TEXT, SimilarityLanguage.forFileName("noextension"))
    }

    @Test
    fun `an exercise graded by Silmused is SQL whatever its solution file is called`() {
        // New exercises start as lahendus.py, and the executor writes every submission under that name
        // whatever the exercise says, which is why Silmused's template renames the file itself. So an
        // SQL exercise very often still says .py, and by name alone its SQL was parsed as Python.
        assertEquals(SimilarityLanguage.SQL, SimilarityLanguage.of("lahendus.py", "silmused"))
        assertEquals(SimilarityLanguage.SQL, SimilarityLanguage.of("lahendus.py", "registry.example.ee/silmused:2.1"))
        // Pinned by digest rather than by tag, which is how an image is named for reproducibility.
        assertEquals(SimilarityLanguage.SQL, SimilarityLanguage.of("lahendus.py", "registry.example.ee/silmused@sha256:0123abcd"))
        assertEquals(SimilarityLanguage.SQL, SimilarityLanguage.of("lahendus.sql", null))
        // Any other grader leaves it to the name.
        assertEquals(SimilarityLanguage.PYTHON, SimilarityLanguage.of("lahendus.py", "tiivad:tsl-compose"))
        assertEquals(SimilarityLanguage.JAVA, SimilarityLanguage.of("Main.java", "some-java-grader"))
        assertEquals(SimilarityLanguage.PYTHON, SimilarityLanguage.of("lahendus.py", "silmused-lookalike"))
    }

    private companion object {
        /** The solutions [failing] fails on. */
        const val CHOKES = "chokes the listener"
        const val OVERFLOWS = "overflows the parser stack"
    }
}
