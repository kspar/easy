package core.aas

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentLinkedQueue
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
 * for the result. Note [scheduleAndAwait] does not start jobs. Use the method [start] for starting scheduled
 * jobs.
 *
 */
class FunctionScheduler<T>(private val function: KFunction<T>) {
    private val jobs = ConcurrentLinkedQueue<EzJob<T>>()

    private data class EzJob<J>(val waitableChannel: Channel<Deferred<J>>, val jobDeferred: Deferred<J>)

    /**
     * Start next [scheduleAndAwait] job.
     *
     *  @return if any job existed and was started
     */
    @Synchronized
    fun startNext(): Boolean {
        val next = getWaiting().firstOrNull()
        next?.jobDeferred?.start()
        next?.waitableChannel?.offer(next.jobDeferred)
        return next != null
    }


    /**
     * Submit and wait for [function] result [T] with given arguments.
     *
     * @param arguments to be passed to [function]
     * @return [function] output
     */
    suspend fun scheduleAndAwait(vararg arguments: Any?): T {
        val job = EzJob(
            Channel(Channel.CONFLATED),
            GlobalScope.async(start = CoroutineStart.LAZY) { function.call(*arguments) }
        )
        jobs.add(job)

        try {
            return job.waitableChannel.receive().await()
        } finally {
            jobs.remove(job)
        }
    }


    /**
     * Are there any not started jobs?
     */
    fun hasWaiting(): Boolean = getWaiting().isNotEmpty()


    /**
     * Number of jobs pending for scheduling, e.g. not yet called with coroutine via [start]?
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
