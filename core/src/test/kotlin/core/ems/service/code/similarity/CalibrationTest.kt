package core.ems.service.code.similarity

import core.ems.service.code.similarity.Calibration.Phase.PAIRS
import core.ems.service.code.similarity.Calibration.Phase.PREPARE
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
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
        assertEquals(1.0, calibration.factor("jplag", PAIRS))
        // Predicted under a second: mostly overhead, and ignored.
        calibration.observe("jplag", PAIRS, measuredNanos = 10 * second, predictedNanos = second / 2)
        assertEquals(1.0, calibration.factor("jplag", PAIRS))
        // Twice the model: half way there from 1.
        calibration.observe("jplag", PAIRS, measuredNanos = 4 * second, predictedNanos = 2 * second)
        assertEquals(1.5, calibration.factor("jplag", PAIRS), 1e-9)
        // Per provider and per phase.
        assertEquals(1.0, calibration.factor("jplag", PREPARE))
        assertEquals(1.0, calibration.factor("edit-distance", PAIRS))
    }

    @Test
    fun `one strange run moves the factor only so far, and it never leaves a quarter to four times`() {
        val calibration = Calibration()
        calibration.observe("edit-distance", PAIRS, measuredNanos = 1_000 * second, predictedNanos = second)
        assertEquals(2.5, calibration.factor("edit-distance", PAIRS), 1e-9)
        repeat(20) { calibration.observe("edit-distance", PAIRS, measuredNanos = 1_000 * second, predictedNanos = second) }
        assertEquals(4.0, calibration.factor("edit-distance", PAIRS), 1e-3)
        repeat(20) { calibration.observe("edit-distance", PAIRS, measuredNanos = 0, predictedNanos = second) }
        assertEquals(0.25, calibration.factor("edit-distance", PAIRS), 1e-3)
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
        val jplag = SimilarityBinding(JPlagProvider.ID, SimilarityLanguage.PYTHON)
        val before = service.estimate(jplag, sized)

        service.compare({ sources }, { it }, jplag, null, 10)

        assertTrue(service.calibration.factor(JPlagProvider.ID, PREPARE) > 2.0, "tokenising scaled by ${service.calibration.factor(JPlagProvider.ID, PREPARE)}")
        assertTrue(service.estimate(jplag, sized).wallNanos > before.wallNanos * 2, "the next estimate did not grow")
    }

    @Test
    fun `pairs slower than the model make the next estimate the longer by as much`() {
        // Each phase scaled by its own factor: edit distance prepares next to nothing, so the estimate of a
        // cohort is its pairs, and twice the model on them is about twice the estimate.
        val service = SimilarityService(maxSubmissions = 100, maxSolutionTokens = 10_000, maxRepetition = 1_000_000, timeoutSec = 600)
        service.threadsFor = { 1 }
        val sized = (1L..60L).map { SizedSource(it, 5_000, "s$it") }
        val editDistance = SimilarityBinding(EditDistanceProvider.ID, SimilarityLanguage.PYTHON)
        val before = service.estimate(editDistance, sized)

        // Three times the model, once: half way there, a factor of two.
        service.calibration.observe(EditDistanceProvider.ID, PAIRS, measuredNanos = 30 * second, predictedNanos = 10 * second)

        val after = service.estimate(editDistance, sized)
        assertTrue(after.wallNanos > before.wallNanos * 1.8, "${before.wallNanos} became ${after.wallNanos}")
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
        val editDistance = SimilarityBinding(EditDistanceProvider.ID, SimilarityLanguage.PYTHON)
        runCatching { service.compare({ sources }, { it }, editDistance, null, 10) }
            .onSuccess { error("a run of 1,770 long pairs finished inside four seconds of reads") }
            .onFailure { assertTrue(it is SimilarityTooCostly, "refused as $it") }
        assertTrue(service.calibration.factor(EditDistanceProvider.ID, PAIRS) != 1.0, "the refused run said nothing")
    }
}
