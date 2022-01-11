package core.aas

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.onClosed
import kotlinx.coroutines.channels.onFailure
import mu.KotlinLogging
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KFunction

private val log = KotlinLogging.logger {}

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
            log.debug { "Job@${job.id}: Job removed (4/4)" }
            jobs.remove(job)
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
