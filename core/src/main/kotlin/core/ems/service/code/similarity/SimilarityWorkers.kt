package core.ems.service.code.similarity

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The threads one comparison runs on - made for it and gone with it - and where its work runs in
 * parallel. (JPlag's SubmissionSet parses on virtual threads of its own, but all it does there is take
 * the tokens made here; see `JPlagProvider.skipReason` for what its failures become.)
 *
 * Every rule about running that work is kept here rather than by each caller, because each one is easy
 * to get subtly wrong and invisible when it is, and two independent reviews of the code this replaced
 * found each of them broken:
 *
 *  - **One failure stops the rest.** The first task to throw raises [stopped]. A task not yet started
 *    then returns without running, and a long one reads [stopped] between its units of work. Before,
 *    an OutOfMemoryError in one tokenisation left the other threads taking submission after submission
 *    off the queue, allocating under the same pressure, until the caller got round to that task's result.
 *  - **Nothing outlives the run.** [all] returns only once every task it was given has finished, and
 *    [close] waits for the threads themselves. So by the time the permit passes to the next comparison,
 *    nothing of this one is still running, which is what "one comparison at a time" claims. It used to be
 *    `shutdownNow()`, which interrupts and does not wait - and neither ANTLR, javac nor the tiling polls
 *    for interruption, so a failed run's last tasks ran on beside the next run.
 *  - **The worst failure is the one reported.** When several tasks fail, an Error wins over any other
 *    exception, and any other over running out of time; the rest ride along as suppressed. Read in list
 *    order, a timeout in the first task hid an OutOfMemoryError in the third, and the endpoint answered
 *    SIMILARITY_TIMEOUT - a named refusal nobody is told about - over a dead heap.
 *
 * Platform threads, [stackBytes] deep, a fixed number of them. Coroutines were weighed and not used.
 * The application uses them for waiting on the executor, which is what they are good at; this work never
 * waits. It is CPU-bound, cannot be cancelled part way through a unit by anything cooperative, since
 * nothing it calls checks, and needs a stack deeper than any shared dispatcher's. A coroutine version
 * would need this same pool underneath and `runBlocking` on the request thread in front, and would add a
 * silent failure of its own: an `async` launched without this pool's dispatcher runs on runBlocking's
 * event loop - the whole cohort one task after another, on the request thread's shallow stack - and still
 * passes every test. Virtual threads cannot be given a stack size at all.
 *
 * Made per comparison rather than kept, so that the stack a deep parse committed goes back to the
 * operating system with the thread, instead of staying resident under a pool that is idle nearly always.
 */
internal class SimilarityWorkers(val threads: Int, stackBytes: Long) : AutoCloseable {

    private val stop = AtomicBoolean()

    private val pool: ExecutorService = run {
        val run = RUNS.incrementAndGet()
        val thread = AtomicInteger()
        // Named per run, so the log lines of one comparison can be told from the next one's.
        Executors.newFixedThreadPool(threads) { task ->
            Thread(null, task, "similarity-$run-${thread.incrementAndGet()}", stackBytes).apply { isDaemon = true }
        }
    }

    /** Whether some task has failed, or the run is being given up; see the class notes. */
    val stopped: Boolean get() = stop.get()

    /**
     * Runs every task and returns their results in order - or throws the worst failure among them, once
     * every one has finished or been skipped.
     *
     * Returns only if no task failed, which is also the only case in which no task can have seen
     * [stopped]: a result can never be one a task cut short because another failed.
     */
    fun <T> all(tasks: List<() -> T>): List<T> {
        val futures = try {
            pool.invokeAll(tasks.map { task -> Callable { if (stop.get()) NotRun else runStopping(task) } })
        } catch (e: InterruptedException) {
            // The caller's own thread, interrupted while waiting - a server shutting down. invokeAll has
            // cancelled what had not finished, and close() waits out what is still running.
            stop.set(true)
            throw e
        }
        val failures = futures.mapNotNull { future ->
            try {
                future.get()
                null
            } catch (e: ExecutionException) {
                e.cause ?: e
            }
        }
        if (failures.isNotEmpty()) throw worstOf(failures)
        return futures.map { future ->
            val result = future.get()
            // Only a failure raises the stop, and there was none - so every task ran. Said out loud, since
            // the cast below would otherwise turn a broken rule into a ClassCastException somewhere else.
            check(result !== NotRun) { "A task was skipped although none failed" }
            @Suppress("UNCHECKED_CAST")
            result as T
        }
    }

    private fun <T> runStopping(task: () -> T): T = try {
        task()
    } catch (t: Throwable) {
        stop.set(true)
        throw t
    }

    /**
     * Stops what has not started, and waits for what has. `ExecutorService.close()` waits for running
     * tasks uninterruptibly: an interrupt meanwhile makes it interrupt the workers - which nothing they
     * run notices - and it goes on waiting, re-asserting the interrupt once they are done, rather than
     * cutting the wait short, which would only release the permit early.
     */
    override fun close() {
        stop.set(true)
        pool.close()
    }

    /** What a task started after the stop returns in place of running. */
    private object NotRun

    companion object {
        private val RUNS = AtomicInteger()

        /**
         * The failure to report: an Error first, then any exception but a timeout, then the timeout;
         * the first of the worst kind, so the answer does not depend on scheduling. The rest are
         * attached to it as suppressed. That includes the reported failure itself, which Kotlin's
         * `addSuppressed` skips where Java's would throw - and it does come up, since the JVM hands out
         * preallocated errors and two tasks can fail with the same instance.
         */
        internal fun worstOf(failures: List<Throwable>): Throwable {
            val worst = failures.minBy { severity(it) }
            failures.forEach { worst.addSuppressed(it) }
            return worst
        }

        private fun severity(failure: Throwable): Int = when (failure) {
            is Error -> 0
            is SimilarityTimedOut, is SimilarityTooCostly -> 2
            else -> 1
        }
    }
}
