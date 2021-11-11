package core.aas

import core.exception.AwaitTimeoutException
import core.exception.ReqError
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import mu.KotlinLogging
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong
import kotlin.reflect.KFunction


private val log = KotlinLogging.logger {}

typealias Ticket = Long

private class BlockingMap<K, V> {
    // https://stackoverflow.com/questions/23917818/concurrenthashmap-wait-for-key-possible
    private val map: MutableMap<K, BlockingQueue<V>> = ConcurrentHashMap()

    @Synchronized
    private fun ensureQueueExists(key: K): BlockingQueue<V> = map.computeIfAbsent(key) { ArrayBlockingQueue(1) }

    operator fun set(key: K, value: V): Boolean = ensureQueueExists(key).add(value)

    fun poll(key: K, timeout: Long, timeUnit: TimeUnit): V? {
        return try {
            ensureQueueExists(key).poll(timeout, timeUnit)
        } finally {
            map.remove(key)
        }
    }

    fun size(): Int = map.size

    fun remove(key: K) = map.remove(key)

    fun values() = map.values.mapNotNull { it.peek() }
}


class FunctionScheduler<T>(private val function: KFunction<T>) {
    private var runningTicket = AtomicLong(0)
    private var runningJobCount = AtomicLong(0)
    private var pendingJobCount = AtomicLong(0)

    /**
     * Holds [JobInfo] that are not yet assigned to coroutine execution.
     */
    private val pendingJobs = ConcurrentLinkedQueue<JobInfo>()

    /**
     * Holds running and finished coroutines.
     */
    private val runningJobs = BlockingMap<Ticket, Deferred<T>>()

    /**
     *
     * @param ticket job ticket that identifies scheduled job.
     * @param arguments to be used on [function].
     *
     */
    private data class JobInfo(val ticket: Ticket, val arguments: Array<Any?>) {
        /**
         *  Implemented [equals] and [hashCode] only based on ticket so that [pendingJobs].contains()
         *  can be called using just the ticket.
         */
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as JobInfo
            return ticket == other.ticket
        }

        override fun hashCode(): Int {
            return ticket.hashCode()
        }
    }


    /**
     * Execute min(number_of_jobs_in_queue, n) jobs.
     */
    fun executeN(n: Int) {
        repeat(n) {
            when (val job = pendingJobs.poll()) {
                null -> return
                else -> {
                    log.debug { "Starting job '${job.ticket}'." }
                    pendingJobCount.decrementAndGet()

                    runningJobs[job.ticket] = GlobalScope.async { function.call(*job.arguments) }
                    runningJobCount.incrementAndGet()
                }
            }
        }
    }

    /**
     * Submit and wait for [function] output with given arguments.
     *
     * @param arguments to be passed to [function]
     * @return [function] output
     */
    suspend fun submitAndAwait(arguments: Array<Any?>, timeout: Long): T {
        return await(submit(arguments), timeout)
    }


    /**
     * Submit a job to scheduled execution, e.g into [pendingJobs].
     */
    private fun submit(arguments: Array<Any?>): Ticket {
        val ticket = runningTicket.incrementAndGet()
        pendingJobCount.incrementAndGet()
        pendingJobs.add(JobInfo(ticket, arguments))
        return ticket
    }

    /**
     * Number of jobs pending for scheduling, e.g. not yet called with coroutine via [executeN]?
     */
    fun countWaiting(): Long {
        return pendingJobCount.get()
    }

    /**
     * Is there any jobs pending for scheduling, e.g. not yet called with coroutine via [executeN]?
     */
    fun hasWaiting(): Boolean {
        return pendingJobs.isNotEmpty()
    }

    /**
     * Number of jobs pending and active. Actual result may not reflect the exact state due to the nature of concurrency.
     */
    fun size(): Long {
        return runningJobCount.get() + pendingJobCount.get()
    }

    /**
     * Wait for job result.
     *
     * Presumes that ticket is valid.
     *
     * It is not exposed as knowing job ticket, any job and therefore any job result could be retrieved.
     */
    @Throws(AwaitTimeoutException::class)
    private suspend fun await(ticket: Ticket, timeout: Long): T {
        log.debug { "Waiting for job '$ticket'." }

        try {
            val deferred = runningJobs.poll(ticket, timeout, TimeUnit.MILLISECONDS)
            val await = deferred?.await()

            log.debug {
                "Finished waiting '$ticket': " +
                        "completed=${deferred?.isCompleted}, " +
                        "canceled=${deferred?.isCancelled}, " +
                        "active=${deferred?.isActive}."
            }

            return await ?: throw AwaitTimeoutException(
                "Maximum run time of $timeout ms reached for job $ticket",
                ReqError.ASSESSMENT_AWAIT_TIMEOUT
            )

        } finally {
            pendingJobs.remove(JobInfo(ticket, emptyArray()))
            runningJobs.remove(ticket)
            runningJobCount.decrementAndGet()
        }
    }

    /**
     * Return number of jobs scheduled to run or which are already running.
     */
    fun countRunning(): Long {
        return runningJobs.values().sumOf { if (it.isActive) 1L else 0L }
    }


    override fun toString(): String {
        return "${javaClass.simpleName}(jobs=${runningJobCount.get() + pendingJobCount.get()})"
    }
}
