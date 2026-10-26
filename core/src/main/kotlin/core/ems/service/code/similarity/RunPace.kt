package core.ems.service.code.similarity

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A run's pace (EZ-1667), measured once a tenth of the time it has left is spent: the [total] units of
 * work - pairs compared, solutions tokenised - done so far say how long all of them will take, and a run
 * that cannot finish is refused then, as too costly, rather than holding the one permit to the deadline
 * and timing out with nothing. The estimate prices a run before it starts, from sizes and a cost model
 * measured on one machine; this measures the run itself, on this one. The stress test of EZ-1667 found the
 * model under by a hundred times for JPlag on large solutions, and by three for edit distance on a slower
 * server: each admitted runs that then timed out.
 *
 * Only for work that would be refused anyway: a run that stops at the deadline with what it found -
 * [PairRanking.rankBounded] - has no use for it. Checked once, and only past [MARGIN] times the time left,
 * since a cold JVM's first units are slower than the rest and a run near the line may still finish.
 */
internal class RunPace(
    private val deadline: Deadline,
    private val budgetSec: Long,
    private val total: Long,
    /** What is counted, for the log line: "pairs", "solutions tokenised". */
    private val what: String,
    /** The estimate to refuse with, from the whole run's projected wall time. */
    private val estimate: (projectedNanos: Long) -> SimilarityEstimate,
) {
    private val log = KotlinLogging.logger {}

    private val started = deadline.ticker.nanos()
    private val left = deadline.remainingNanos()
    private val checkAfter = left / CHECK_AFTER_SHARE
    private val done = AtomicLong()
    private val checked = AtomicBoolean()

    /** One more unit done; refused with [SimilarityTooCostly] if this is the one the pace is read at, and it says no. */
    fun done() {
        val n = done.incrementAndGet()
        if (checked.get()) return
        val elapsed = deadline.ticker.nanos() - started
        if (elapsed < checkAfter || !checked.compareAndSet(false, true)) return
        val projected = elapsed * (total.toDouble() / n)
        if (projected <= left * MARGIN) return
        val refused = estimate(budgetSec * 1_000_000_000L - left + projected.toLong())
        log.info { "Refusing a comparison at its pace: $n of $total $what in ${elapsed / 1_000_000}ms, so about ${refused.seconds}s in all, over the ${budgetSec}s budget" }
        throw SimilarityTooCostly(refused)
    }

    companion object {
        /** The pace is read once this share of the time left is spent: a tenth. */
        private const val CHECK_AFTER_SHARE = 10

        /** How far past the time left the pace must project before the run is refused. */
        private const val MARGIN = 1.5
    }
}
