package core.ems.service.code.similarity

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A run's pace (EZ-1667), measured once a tenth of the time it has left is spent: the share of its [total]
 * work done so far - pairs compared, solutions tokenised - says how long all of it will take, and a run that
 * cannot finish is refused then, as too costly, rather than holding the one permit to the deadline and
 * timing out with nothing. It measures the run itself, on this server, which is the only thing that can
 * say: nothing is predicted from sizes. A model that did, measured on one machine, was out by a hundred
 * times for JPlag on large solutions and by four for edit distance in the stress test of EZ-1667.
 *
 * Counted in pieces, which differ in cost, so the first tenth must be a fair share of them: the work it
 * counts is walked in [fairOrder] - JPlag's tokenising, a full run's pairs - as the database's order could
 * put a cohort's few large solutions all first, and their pieces be read as the pace of the whole.
 *
 * Only for work that would be refused anyway: a run that stops at the deadline with what it found -
 * [PairRanking.rankBounded] - has no use for it. Checked once, and only past [MARGIN] times the time left,
 * since a cold JVM's first units are slower than the rest and a run near the line may still finish.
 */
internal class RunPace(
    private val deadline: Deadline,
    private val budgetSec: Long,
    private val total: Long,
    /** What is counted, for the log line: "pairs", "tokenising". */
    private val what: String,
    /** Whether this is preparing, before any pair: then what it projects is the least the run would take. */
    private val preparing: Boolean,
) {
    private val log = KotlinLogging.logger {}

    private val started = deadline.ticker.nanos()
    private val left = deadline.remainingNanos()
    private val checkAfter = left / CHECK_AFTER_SHARE
    private val done = AtomicLong()
    private val checked = AtomicBoolean()

    /** [units] more of [total] done; refused with [SimilarityTooCostly] if this is when the pace is read, and it says no. */
    fun done(units: Long = 1) {
        val n = done.addAndGet(units)
        if (checked.get()) return
        val elapsed = deadline.ticker.nanos() - started
        if (elapsed < checkAfter || !checked.compareAndSet(false, true)) return
        val projected = elapsed * (total.toDouble() / n)
        if (projected <= left * MARGIN) return
        val refused = SimilarityProjection(budgetSec * 1_000_000_000L - left + projected.toLong(), budgetSec, preparing)
        log.info {
            "Refusing a comparison at its pace: ${n * 100 / total}% of its $what in ${elapsed / 1_000_000}ms, so about " +
                "${refused.seconds}s ${if (preparing) "before any pair" else "in all"}, over the ${budgetSec}s budget"
        }
        throw SimilarityTooCostly(refused)
    }

    companion object {
        /**
         * The order to walk [size] pieces of work in, the [n]th known by [id], for the pace to read a fair share
         * of them first: shuffled by id, and the same every time, for the same answer every time.
         */
        fun fairOrder(size: Int, id: (n: Int) -> Long): List<Int> = (0 until size).sortedBy { id(it) * SHUFFLE }

        /** An odd multiplier near 2^64 over the golden ratio: ids in a row, multiplied by it, land far apart. */
        private const val SHUFFLE = -7046029254386353131L

        /** The pace is read once this share of the time left is spent: a tenth. */
        private const val CHECK_AFTER_SHARE = 10

        /** How far past the time left the pace must project before the run is refused. */
        private const val MARGIN = 1.5
    }
}
