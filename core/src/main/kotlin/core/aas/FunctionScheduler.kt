package core.aas

import core.exception.AwaitTimeoutException
import core.exception.ReqError
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong
import kotlin.reflect.KFunction


typealias Ticket = Long

private class BlockingMap<K, V> {
    // https://stackoverflow.com/questions/23917818/concurrenthashmap-wait-for-key-possible
    private val map: MutableMap<K, BlockingQueue<V>> = ConcurrentHashMap()

    @Synchronized
    private fun ensureQueueExists(key: K): BlockingQueue<V> = map.computeIfAbsent(key) { ArrayBlockingQueue(1) }

    operator fun set(key: K, value: V): Boolean = ensureQueueExists(key).add(value)

    fun poll(key: K, timeout: Long): V? {
        return try {
            ensureQueueExists(key).poll(timeout, TimeUnit.MILLISECONDS)
        } finally {
            remove(key)
        }
    }

    fun remove(key: K) = map.remove(key)

    fun values() = map.values.mapNotNull { it.peek() }
}


class FunctionScheduler<T>(private val function: KFunction<T>) {
    private var runningTicket = AtomicLong(0)

    // Holds submitted but not started job info. Pair: ticket to function arguments
    private val waitingJobs = ConcurrentLinkedQueue<Pair<Ticket, Array<Any?>>>()

    // Holds started and completed coroutines.
    private val startedJobs = BlockingMap<Ticket, Deferred<T>>()

    /**
     * Start n submitted jobs.
     */
    fun start(n: Int) {
        repeat(n) {
            when (val job = waitingJobs.poll()) {
                null -> return
                else -> startedJobs[job.first] = GlobalScope.async { function.call(*job.second) }
            }
        }
    }


    /**
     * Submit and wait for [function] result with given arguments.
     *
     * @param arguments to be passed to [function]
     * @return [function] output
     */
    suspend fun scheduleAndAwait(arguments: Array<Any?>, timeout: Long): T {
        val ticket = runningTicket.incrementAndGet()

        return try {
            waitingJobs.add(ticket to arguments)
            startedJobs.poll(ticket, timeout)?.await() ?: throw AwaitTimeoutException(
                "Timeout of '$timeout' ms reached for job $ticket", ReqError.ASSESSMENT_AWAIT_TIMEOUT
            )

        } finally {
            waitingJobs.removeAll { it.first == ticket }
            startedJobs.remove(ticket)
        }
    }


    /**
     * Are there any not started jobs?
     */
    fun hasWaiting(): Boolean = waitingJobs.isNotEmpty()


    /**
     * Number of jobs pending for scheduling, e.g. not yet called with coroutine via [start]?
     */
    fun countWaiting(): Int = waitingJobs.size


    /**
     * Return number of jobs started.
     */
    fun countStarted(): Long = startedJobs.values().sumOf { if (it.isActive) 1L else 0L }


    /**
     * Number of jobs started and waiting jobs. Actual result may not reflect the exact state due to the concurrency.
     */
    fun size(): Long = countStarted() + countWaiting()

    override fun toString(): String = "${javaClass.simpleName}(jobs=${size()})"
}
