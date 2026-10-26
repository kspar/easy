package core.ems.service.code.similarity

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * How this server's runs compare with the cost model (EZ-1667), kept per provider, language and phase.
 *
 * Each provider's [SimilarityProvider.prepareNanos] and [SimilarityProvider.pairNanos] were measured on
 * the machines this was written on, and the estimate that refuses a run up front, or tells a teacher how
 * long it will take, is only as right as that machine is like this one. Edit distance ran three times
 * slower than its model in the EZ-1667 stress test; on a server three times faster, its full runs would be
 * refused for taking three times what they do, and nothing at run time would put that right - the pace
 * check only catches estimates that came out too low.
 *
 * So every run that is large enough to say something is measured against what the model predicted for
 * it, and the next estimate is scaled by the ratio - a running average, each run's ratio clamped to
 * [MIN_RATIO]..[MAX_RATIO] so that one strange cohort or a cold JVM cannot swing it far. Per language, as
 * the model is wrong by different amounts for each: JPlag parses Python and Java at costs unlike its text
 * tokeniser's, and kept per provider, an essay exercise falling back to text moved the factor every
 * Python cohort was then priced by. It lives in memory and starts at 1 after a restart: the first runs are
 * estimated as the model has it, and set it.
 */
internal class Calibration {
    private val log = KotlinLogging.logger {}

    enum class Phase { PREPARE, PAIRS }

    private data class Key(val provider: String, val language: SimilarityLanguage, val phase: Phase)

    private val factors = ConcurrentHashMap<Key, Double>()

    /** What [provider]'s model is multiplied by for [phase] in [language] on this server: 1 until a run has said otherwise. */
    fun factor(provider: String, language: SimilarityLanguage, phase: Phase): Double = factors[Key(provider, language, phase)] ?: 1.0

    /**
     * A run of [provider] in [language] spent [measuredNanos] on [phase] where the model predicted
     * [predictedNanos], both in one worker's time. Ignored below [MIN_PREDICTED_NANOS] predicted: a small
     * run is mostly overhead, and would set the factor by that.
     */
    fun observe(provider: String, language: SimilarityLanguage, phase: Phase, measuredNanos: Long, predictedNanos: Long) {
        if (predictedNanos < MIN_PREDICTED_NANOS) return
        val ratio = (measuredNanos.toDouble() / predictedNanos).coerceIn(MIN_RATIO, MAX_RATIO)
        val factor = factors.merge(Key(provider, language, phase), (1 - WEIGHT) + WEIGHT * ratio) { old, _ ->
            (old * (1 - WEIGHT) + ratio * WEIGHT).coerceIn(MIN_RATIO, MAX_RATIO)
        }
        log.info { "Similarity cost model for $provider ${language.wireName} ${phase.name.lowercase()}: ${measuredNanos / 1_000_000}ms against ${predictedNanos / 1_000_000}ms predicted, now scaled by %.2f".format(factor) }
    }

    companion object {
        /** A run predicted under a second of one worker's time says little about the server. */
        const val MIN_PREDICTED_NANOS = 1_000_000_000L

        /**
         * How far one run moves the factor towards its own ratio: a fifth, so a run on a server busy with
         * something else - grading, a deploy - lifts the estimates that follow by a little, not by half again.
         */
        private const val WEIGHT = 0.2

        private const val MIN_RATIO = 0.25
        private const val MAX_RATIO = 4.0
    }
}

/**
 * The time some work took, in one worker's nanoseconds - each piece timed on the worker that did it - beside
 * what the cost model predicted for the same pieces: what [Calibration] learns from.
 */
internal class Spent {
    val measured = AtomicLong()
    val predicted = AtomicLong()

    fun add(measuredNanos: Long, predictedNanos: Long) {
        measured.addAndGet(measuredNanos)
        predicted.addAndGet(predictedNanos)
    }
}
