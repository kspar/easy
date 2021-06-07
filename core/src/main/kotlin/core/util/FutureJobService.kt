package core.util

import core.exception.AwaitTimeoutException
import core.exception.ReqError
import kotlinx.coroutines.*
import mu.KotlinLogging
import org.joda.time.DateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.reflect.KFunction

private val log = KotlinLogging.logger {}

typealias Ticket = Long

class FutureJobService<T>(private val futureCall: KFunction<T>) {
    private var runningTicket = AtomicLong(0)

    /**
     * Holds [JobInfo] that are not yet assigned to coroutine execution.
     */
    private val pendingQueue = ConcurrentLinkedQueue<JobInfo>()

    /**
     * Holds [JobInfo] assigned to coroutine. The finished coroutine results will be made available in this map.
     */
    private val assignedMap = ConcurrentHashMap<Ticket, DeferredOutput<T>>()

    private data class DeferredOutput<E>(val ticket: Ticket, val submitted: Long, val deferred: Deferred<E>)

    /**
     *
     * @param ticket job ticket that identifies scheduled job.
     * @param arguments to be used on [futureCall].
     *
     */
    private data class JobInfo(val ticket: Ticket, val arguments: Array<Any?>) {

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
    fun executeN(dispatcher: CoroutineDispatcher, n: Int) {
        run executeIfNotEmpty@{
            repeat(n) {
                when (val job = pendingQueue.poll()) {
                    null -> return@executeIfNotEmpty
                    else -> {
                        log.debug { "Setting job with ticket '${job.ticket}' to coroutine run." }
                        assignedMap[job.ticket] = DeferredOutput(
                            job.ticket,
                            DateTime.now().millis,
                            CoroutineScope(dispatcher).async { futureCall.call(*job.arguments) })
                    }
                }
            }
        }
    }

    /**
     * Submit and wait for [futureCall] output with given arguments.
     *
     * @param arguments to be passed to [futureCall]
     */
    fun submitAndAwait(arguments: Array<Any?>, timeout: Long): T {
        return await(submit(arguments), timeout)
    }


    /**
     * Submit a job to scheduled execution, e.g into [pendingQueue].
     */
    private fun submit(arguments: Array<Any?>): Ticket {
        val ticket = runningTicket.incrementAndGet()
        pendingQueue.add(JobInfo(ticket, arguments))
        return ticket
    }


    /**
     * Job in [pendingQueue], e.g. not yet called with coroutine?
     */
    private fun isPending(ticket: Ticket): Boolean {
        return pendingQueue.contains(JobInfo(ticket, emptyArray()))
    }


    private fun isTimeOut(endTime: Long): Boolean {
        return DateTime.now().millis > endTime
    }

    private fun throwTimeOut(reason: String) {
        throw AwaitTimeoutException(
            "Scheduled job has been cancelled due to timeout. Reason: $reason.",
            ReqError.ASSESSMENT_AWAIT_TIMEOUT
        )
    }

    /**
     * Get result from [assignedMap].
     */
    private fun waitResult(ticket: Ticket): T {
        return runBlocking {
            val deferred = assignedMap.remove(ticket)?.deferred?.await()
            if (deferred == null) throwTimeOut("Long running time")
            deferred!!
        }
    }

    /**
     * Wait for job result.
     *
     * Presumes that ticket is valid.
     *
     * It is not exposed as knowing job ticket, any job and therefore any job result could be retrieved.
     */
    @Throws(AwaitTimeoutException::class)
    private fun await(ticket: Ticket, timeout: Long): T {
        val endTime = DateTime.now().millis + timeout

        try {
            while (true) {
                when {
                    isTimeOut(endTime) -> throwTimeOut("Long wait in queue")
                    isPending(ticket) -> Thread.sleep(1000)
                    else -> waitResult(ticket)
                }
            }

        } catch (ex: CancellationException) {
            throwTimeOut("Long running time")
        } finally {
            pendingQueue.remove(JobInfo(ticket, emptyArray()))
            assignedMap.remove(ticket)
        }

        // Should never be here.
        throw RuntimeException()
    }

    /**
     * Return number of jobs scheduled to run or which are already running.
     */
    fun countActive(): Long {
        return assignedMap.values.sumOf { if (it.deferred.isActive) 1L else 0L }
    }

    /**
     * Clear job results, which were not retrieved or are still running. Return number of jobs cleared.
     *
     * According to the ConcurrentHashMap documentation: iterators are designed to be used by only one thread at a time.
     */
    @Synchronized
    fun clearOlder(timeout: Long): Long {
        var removed = 0L
        val currentTime = DateTime.now().millis
        assignedMap.values.removeIf {
            val remove = it.submitted + timeout < currentTime
            if (remove) {
                it.deferred.cancel()
                removed++
            }
            remove
        }
        return removed
    }
}
