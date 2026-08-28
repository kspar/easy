package core.aas

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.onClosed
import kotlinx.coroutines.channels.onFailure
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KFunction


/**
 *
 * A scheduler system for parallel processing of a [KFunction] with coroutines.
 *
 * @param function a function to be scheduled.
 * @param T the return type of the scheduled function.
 *
 *
 * Use the method [scheduleAndAwait] to schedule [function] to be executed in the future with given arguments and wait
 * for the result. Note [scheduleAndAwait] does not start jobs. Use the method [startNext] for starting scheduled
 * jobs.
 *
 */
class FunctionScheduler<T>(private val function: KFunction<T>) {
    private val log = KotlinLogging.logger {}

    private val jobs = ConcurrentLinkedQueue<EzJob<T>>()
    private val closed = AtomicBoolean(false)

    private data class EzJob<J>(
        val id: String,
        val waitableChannel: Channel<Deferred<J>?>,
        val jobDeferred: Deferred<J>
    )

    /**
     * Start next [scheduleAndAwait] job.
     *
     *  @return if any job existed and was started
     */
    fun startNext(): Boolean {
        return synchronized(this) {
            val next = getWaiting().firstOrNull()?.also { log.debug { "Job@${it.id}: Starting (1/2)" } }
            next?.jobDeferred?.start()
            next?.also { log.debug { "Job@${it.id}: Started (2/2)" } }
            next?.waitableChannel?.trySend(next.jobDeferred)
                ?.onFailure { log.error { "Job@${next.id}: Channel failed with ${it?.stackTrace}" } }
                ?.onClosed { log.warn { "Job@${next.id}: Channel is closed ${it?.stackTrace}" } }
            next != null
        }
    }


    /**
     * Submit and wait for [function] result [T] with given arguments.
     *
     * @param arguments to be passed to [function]
     * @return [function] output
     */
    @OptIn(DelicateCoroutinesApi::class)
    suspend fun scheduleAndAwait(vararg arguments: Any?): T {
        val job = synchronized(this) {
            if (closed.get()) throw ExecutorException("Scheduler is killed")

            val job = EzJob(
                UUID.randomUUID().toString(),
                Channel(Channel.CONFLATED),
                GlobalScope.async(start = CoroutineStart.LAZY) { function.call(*arguments) }
            )
            jobs.add(job)

            job
        }


        try {
            log.debug { "Job@${job.id}: Listening on channel (1/4)" }
            val channel = job.waitableChannel.receive()
            log.debug { "Job@${job.id}: Waiting for job to complete on channel '$channel' (2/4)" }
            val result = channel?.await() ?: throw ExecutorException("Job@${job.id}: Scheduler was killed")
            log.debug { "Job@${job.id}: Job finished (3/4)" }
            return result
        } finally {
            // **A started job stays in `jobs` until it actually completes.**
            //
            // This used to be an unconditional `jobs.remove(job)`. On the normal and the error paths
            // that is right — the deferred is finished by the time `await()` returns or throws. But if
            // the *awaiting* coroutine is cancelled while a started job is still in flight, removing it
            // dropped a job that was still occupying a slot on the executor, and `jobs` is the
            // admission control for the whole pipeline: `AutoGradeScheduler.grade` admits
            // `min(waiting, maxLoad - countActive())`, `chooseOptimalExecutor` ranks by
            // `size() / maxLoad`, and `deleteExecutor` refuses while `size()` is non-zero. An
            // undercount there lets core push a grading host past the one limit that protects it.
            //
            // Cancelling on the way out — the obvious fix, and the one the review suggested — would not
            // help, because the scheduled function is `callExecutor`: a synchronous `RestTemplate` POST
            // whose read timeout defaults to an hour. Coroutine cancellation is cooperative, so the
            // request runs to completion or timeout regardless, and the slot stays occupied whether or
            // not the deferred says it is cancelled. Only continuing to count it tells the truth.
            //
            // `invokeOnCompletion` is what removes it afterwards, since by then there is no awaiter
            // left to do it. It fires on cancellation too, so `killScheduler` still drains cleanly.
            val deferred = job.jobDeferred
            if (deferred.isActive) {
                log.debug { "Job@${job.id}: Awaiter gone, still running — kept counted (4/4)" }
                deferred.invokeOnCompletion { jobs.remove(job) }
            } else {
                // Completed, or lazy and never started. In the latter case nobody will ever await it,
                // so cancel to release the coroutine — `startNext()` cannot reach it once it is out of
                // `jobs` either way, so this is tidiness rather than a behaviour change.
                if (!deferred.isCompleted) deferred.cancel()
                log.debug { "Job@${job.id}: Job removed (4/4)" }
                jobs.remove(job)
            }
        }
    }

    fun killScheduler() {
        synchronized(this) {
            closed.set(true)
            jobs.forEach {
                log.debug { "Job@${it.id}: Killing (it)" }
                it.jobDeferred.cancel()
                it.waitableChannel.trySend(null)
            }
            log.debug { "Clearing jobs" }
            jobs.clear()
        }
    }


    /**
     * Are there any not started jobs?
     */
    fun hasWaiting(): Boolean = getWaiting().isNotEmpty()


    /**
     * Number of jobs pending for scheduling, e.g. not yet called with coroutine via [startNext].
     */
    fun countWaiting(): Int = getWaiting().size

    /**
     * Return number of jobs started.
     */
    fun countActive(): Int = jobs.filter { it.jobDeferred.isActive }.size


    /**
     * Number of jobs started and waiting jobs. Actual result may not reflect the exact state due to the concurrency.
     */
    fun size(): Int = jobs.size

    private fun getWaiting() = jobs.filter { !it.jobDeferred.isCompleted && !it.jobDeferred.isActive }

    override fun toString(): String = "${javaClass.simpleName}(jobs=${size()})"
}
