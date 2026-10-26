package core.ems.service.code.similarity

import core.ems.service.code.similarity.Calibration.Phase.PAIRS
import core.ems.service.code.similarity.Calibration.Phase.PREPARE
import core.ems.service.code.similarity.SimilarityLanguage.PYTHON
import core.ems.service.code.similarity.SimilarityLanguage.TEXT
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

/**
 * The estimate scaled by how this server's runs compare with the cost model (EZ-1667): the constants were
 * measured on one machine, and on a faster one they refused runs that would have fit, with nothing at run
 * time to put it right.
 */
@Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class CalibrationTest {
    private val second = 1_000_000_000L

    @Test
    fun `the model stands until a run large enough to say something says otherwise`() {
        val calibration = Calibration()
        assertEquals(1.0, calibration.factor("jplag", PYTHON, PAIRS))
        // Predicted under a second: mostly overhead, and ignored.
        calibration.observe("jplag", PYTHON, PAIRS, measuredNanos = 10 * second, predictedNanos = second / 2)
        assertEquals(1.0, calibration.factor("jplag", PYTHON, PAIRS))
        // Twice the model: a fifth of the way there from 1, as one run on a busy server is not the server.
        calibration.observe("jplag", PYTHON, PAIRS, measuredNanos = 4 * second, predictedNanos = 2 * second)
        assertEquals(1.2, calibration.factor("jplag", PYTHON, PAIRS), 1e-9)
        // Per provider, per language and per phase.
        assertEquals(1.0, calibration.factor("jplag", PYTHON, PREPARE))
        assertEquals(1.0, calibration.factor("jplag", TEXT, PAIRS))
        assertEquals(1.0, calibration.factor("edit-distance", PYTHON, PAIRS))
    }

    @Test
    fun `one strange run moves the factor only so far, and it never leaves a quarter to four times`() {
        val calibration = Calibration()
        calibration.observe("edit-distance", PYTHON, PAIRS, measuredNanos = 1_000 * second, predictedNanos = second)
        assertEquals(1.6, calibration.factor("edit-distance", PYTHON, PAIRS), 1e-9)
        repeat(50) { calibration.observe("edit-distance", PYTHON, PAIRS, measuredNanos = 1_000 * second, predictedNanos = second) }
        assertEquals(4.0, calibration.factor("edit-distance", PYTHON, PAIRS), 1e-3)
        repeat(50) { calibration.observe("edit-distance", PYTHON, PAIRS, measuredNanos = 0, predictedNanos = second) }
        assertEquals(0.25, calibration.factor("edit-distance", PYTHON, PAIRS), 1e-3)
    }

    @Test
    fun `a server slower than the model at tokenising estimates its next runs the longer for it`() {
        // Each solution takes 200 ms of the test's clock to tokenise, where the model says about 20: the run
        // tells the calibration, and the same cohort's next estimate is the longer for it.
        val clock = ManualTicker()
        val service = SimilarityService(maxSubmissions = 100, maxSolutionTokens = 10_000, maxRepetition = 1_000_000, timeoutSec = 600, ticker = clock)
        service.threadsFor = { 1 }
        val read = service.tokenise
        service.tokenise = { language, file, source, max -> clock.advance(200); read(language, file, source, max) }
        val random = Random(1667)
        val sources = (1L..60L).map { id ->
            SimilaritySource(id, "def f$id(x):\n" + (1..10).joinToString("") { "    x = x + ${random.nextInt(100)}\n" } + "    return x\n", "s$id")
        }
        val sized = sources.map { SizedSource(it.id, utf8Length(it.solution), it.owner) }
        val jplag = SimilarityBinding(JPlagProvider.ID, PYTHON)
        val before = service.estimate(jplag, sized)

        service.compare({ sources }, { it }, jplag, null, 10)

        assertTrue(service.calibration.factor(JPlagProvider.ID, PYTHON, PREPARE) > 1.5, "tokenising scaled by ${service.calibration.factor(JPlagProvider.ID, PYTHON, PREPARE)}")
        assertTrue(service.estimate(jplag, sized).wallNanos > before.wallNanos * 1.4, "the next estimate did not grow")
    }

    @Test
    fun `a cohort refused while tokenising still tells the calibration, and its refusal counts the pairs to come`() {
        // 400 ms of the test's clock a solution against the model's 122: refused at its pace a tenth of the
        // way into the budget, it is the run that says most about the model, and it said nothing - the same
        // cohort was admitted, and refused, on every click. Its refusal said how long tokenising would take
        // alone, though the 19,900 pairs after it take far longer, so a group that looked as if it would fit
        // did not.
        val clock = ManualTicker()
        val service = SimilarityService(maxSubmissions = 300, maxSolutionTokens = 10_000, maxRepetition = 1_000_000, timeoutSec = 40, ticker = clock)
        service.threadsFor = { 1 }
        val read = service.tokenise
        service.tokenise = { language, file, source, max -> clock.advance(400); read(language, file, source, max) }
        val long = "x = 1\n".repeat(8_500)
        val sources = (1L..200L).map { id -> SimilaritySource(id, long + "print($id)\n", "s$id") }
        val jplag = SimilarityBinding(JPlagProvider.ID, PYTHON)

        val refused = assertThrows<SimilarityTooCostly> { service.compare({ sources }, { it }, jplag, null, 10) }

        assertTrue(service.calibration.factor(JPlagProvider.ID, PYTHON, PREPARE) > 1.3, "tokenising scaled by ${service.calibration.factor(JPlagProvider.ID, PYTHON, PREPARE)}")
        assertEquals(19_900, refused.estimate.pairs)
        // Tokenising alone projects 80 s; the pairs of 51 KB solutions are most of an hour on one thread.
        assertTrue(refused.estimate.seconds > 1_000, "about ${refused.estimate.seconds}s, the pairs left out")
    }

    @Test
    fun `preparing is timed on the worker that did it, so a slow solution counts once, not once a thread`() {
        // Each worker on a clock of its own: one solution takes 1.5 s of its worker's, the others nothing. As
        // the phase's wall time times the pool, that counted four times over - one pathological solution and
        // every later estimate of the provider was 2.5 times too long.
        val clock = PerThreadTicker()
        val service = SimilarityService(maxSubmissions = 100, maxSolutionTokens = 10_000, maxRepetition = 1_000_000, timeoutSec = 600, ticker = clock)
        service.threadsFor = { 4 }
        val read = service.tokenise
        service.tokenise = { language, file, source, max -> if ("slow" in source) clock.advance(1_500); read(language, file, source, max) }
        val sources = (1L..60L).map { id -> SimilaritySource(id, "def f$id(x):\n    return x + $id\n" + (if (id == 60L) "# slow\n" else ""), "s$id") }

        service.compare({ sources }, { it }, SimilarityBinding(JPlagProvider.ID, PYTHON), null, 10)

        val predicted = sources.sumOf { JPlagProvider(10_000, 1_000_000).prepareNanos(utf8Length(it.solution)) }
        assertEquals(0.8 + 0.2 * (1.5 * second / predicted), service.calibration.factor(JPlagProvider.ID, PYTHON, PREPARE), 1e-9)
    }

    @Test
    fun `each language is scaled by its own runs, so an essay compared as text does not move the Python estimate`() {
        val service = SimilarityService(maxSubmissions = 100, maxSolutionTokens = 10_000, maxRepetition = 1_000_000, timeoutSec = 600)
        service.threadsFor = { 1 }
        val sized = (1L..60L).map { SizedSource(it, 2_000, "s$it") }
        val python = SimilarityBinding(JPlagProvider.ID, PYTHON)
        val text = SimilarityBinding(JPlagProvider.ID, TEXT)
        val pythonBefore = service.estimate(python, sized)
        val textBefore = service.estimate(text, sized)

        service.calibration.observe(JPlagProvider.ID, TEXT, PREPARE, measuredNanos = 30 * second, predictedNanos = 10 * second)

        assertEquals(pythonBefore.wallNanos, service.estimate(python, sized).wallNanos)
        assertTrue(service.estimate(text, sized).wallNanos > textBefore.wallNanos, "the text estimate did not move")
    }

    @Test
    fun `pairs slower than the model make the next estimate the longer by as much`() {
        // Each phase scaled by its own factor: edit distance prepares next to nothing, so the estimate of a
        // cohort is its pairs, and the factor on them is the factor on the estimate.
        val service = SimilarityService(maxSubmissions = 100, maxSolutionTokens = 10_000, maxRepetition = 1_000_000, timeoutSec = 600)
        service.threadsFor = { 1 }
        val sized = (1L..60L).map { SizedSource(it, 5_000, "s$it") }
        val editDistance = SimilarityBinding(EditDistanceProvider.ID, PYTHON)
        val before = service.estimate(editDistance, sized)

        // Three times the model, once: a fifth of the way there, 1.4.
        service.calibration.observe(EditDistanceProvider.ID, PYTHON, PAIRS, measuredNanos = 30 * second, predictedNanos = 10 * second)

        val after = service.estimate(editDistance, sized)
        assertEquals(1.4, after.wallNanos.toDouble() / before.wallNanos, 0.01)
        assertEquals(before.pairs, after.pairs)
    }

    @Test
    fun `a run refused at its pace still tells the calibration what it measured`() {
        // Time spent as the run reads it, so the measure is the same on any machine: a full edit-distance run
        // of long solutions, refused part way, and the factor moved by the pairs it did score. Four seconds,
        // so that the pairs scored before the pace is read are predicted at over the second a run must be to
        // say anything.
        val service = SimilarityService(
            maxSubmissions = 100, maxSolutionTokens = 10_000, maxRepetition = 1_000_000, timeoutSec = 4,
            ticker = SteppingTicker(1_000_000),
        )
        service.threadsFor = { 2 }
        val random = Random(7)
        val sources = (1L..60L).map { id ->
            SimilaritySource(id, (1..1_200).joinToString("") { "    v${random.nextInt(1000)} = ${random.nextInt(1000)}\n" }, "s$id")
        }
        val editDistance = SimilarityBinding(EditDistanceProvider.ID, PYTHON)
        runCatching { service.compare({ sources }, { it }, editDistance, null, 10) }
            .onSuccess { error("a run of 1,770 long pairs finished inside four seconds of reads") }
            .onFailure { assertTrue(it is SimilarityTooCostly, "refused as $it") }
        assertTrue(service.calibration.factor(EditDistanceProvider.ID, PYTHON, PAIRS) != 1.0, "the refused run said nothing")
    }
}
