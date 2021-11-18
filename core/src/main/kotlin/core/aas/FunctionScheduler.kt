package core.aas

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.reflect.KFunction


data class EzJob<J>(
    val waitableChannel: Channel<Deferred<J>>,
    val jobDeferred: Deferred<J>,
)

/**
 * TODO: DOCUMENTATION
 *
 * A scheduler system for parallel processing of a fixed function with coroutines.
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

    /**
     * Start n submitted jobs.
     */
    @Synchronized
    fun start(n: Int) {
        getWaiting().take(n).forEach {
            it.jobDeferred.start()
            it.waitableChannel.offer(it.jobDeferred)
        }
    }


    /**
     * Submit and wait for [function] result with given arguments.
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
    fun hasWaiting(): Boolean = jobs.isNotEmpty()


    private fun getWaiting() = jobs.filter { !it.jobDeferred.isCompleted && !it.jobDeferred.isActive }

    /**
     * Number of jobs pending for scheduling, e.g. not yet called with coroutine via [start]?
     */
    fun countWaiting(): Int = getWaiting().size


    /**
     * Return number of jobs started.
     */
    fun countStarted(): Int = jobs.filter { it.jobDeferred.isActive }.size


    /**
     * Number of jobs started and waiting jobs. Actual result may not reflect the exact state due to the concurrency.
     */
    fun size(): Int = jobs.size

    override fun toString(): String = "${javaClass.simpleName}(jobs=${size()})"
}
