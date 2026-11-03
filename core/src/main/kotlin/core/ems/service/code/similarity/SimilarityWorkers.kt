package core.ems.service.code.similarity

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The threads one comparison runs on, made for it and gone with it, and its [deadline].
 *
 * Platform threads with a deep stack ([STACK_BYTES]): the parsers are recursive descent, and virtual threads
 * cannot be given a stack size. Made per comparison, so the stack a deep parse committed goes back with the
 * thread instead of staying resident under an idle pool.
 *
 * - **One failure stops the rest.** The first task to throw raises [stopped]; a task not yet started then
 *   does not run, and a long one reads [stopped] between its units of work.
 * - **Nothing outlives the run.** [all] returns once every task has finished, and [close] waits for the
 *   threads, so nothing of this comparison is running when the permit passes to the next one. Nothing they
 *   run polls for interruption, so interrupting them would not stop them.
 * - **The worst failure is reported:** an Error over any exception, and any exception over running out of
 *   time - an OutOfMemoryError must not read as a timeout.
 */
internal class SimilarityWorkers(val threads: Int, private val deadline: Deadline) : AutoCloseable {

    private val stop = AtomicBoolean()

    private val pool: ExecutorService = run {
        val thread = AtomicInteger()
        Executors.newFixedThreadPool(threads) { task ->
            Thread(null, task, "similarity-${thread.incrementAndGet()}", STACK_BYTES).apply { isDaemon = true }
        }
    }

    val stopped: Boolean get() = stop.get()

    /** Read wherever the work can stop: before each solution is read and before each pair is compared. */
    fun checkDeadline() {
        if (deadline.passed()) throw SimilarityTimedOut()
    }

    /** Runs every task and returns their results in order, or throws the worst failure among them. */
    fun <T> all(tasks: List<() -> T>): List<T> {
        val futures = pool.invokeAll(
            tasks.map { task ->
                Callable {
                    if (stop.get()) return@Callable null
                    try {
                        task()
                    } catch (t: Throwable) {
                        stop.set(true)
                        throw t
                    }
                }
            },
        )
        val failures = futures.mapNotNull { future ->
            try {
                future.get()
                null
            } catch (e: ExecutionException) {
                e.cause ?: e
            }
        }
        if (failures.isNotEmpty()) {
            val worst = failures.minBy { if (it is Error) 0 else if (it is SimilarityTimedOut) 2 else 1 }
            failures.filter { it !== worst }.forEach { worst.addSuppressed(it) }
            throw worst
        }
        // No task failed, so none saw the stop and every one ran.
        @Suppress("UNCHECKED_CAST")
        return futures.map { it.get() as T }
    }

    override fun close() {
        stop.set(true)
        pool.close()
    }

    private companion object {
        /**
         * 64 MB rather than the JVM's 1 MB: on 1 MB, code CPython accepts - `not` repeated thousands of
         * times - overflowed the parser, which skipped a copy that still ran. Pages are committed only as
         * the stack reaches them.
         */
        const val STACK_BYTES = 64L shl 20
    }
}
