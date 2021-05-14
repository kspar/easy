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

    // Jobs not yet assigned to coroutine execution.
    private val jobQueue = ConcurrentLinkedQueue<JobInfo>()

    // Technically deferred result map. Call + insert job here. In the future, the job result will become available here.
    private val activeJobMap = ConcurrentHashMap<Long, DeferredJobResult<T>>()

    private data class DeferredJobResult<E>(val ticket: Ticket, val submitted: Long, val result: Deferred<E>)

    // Equals is defined only by job ticket  (e.g ID).
    private data class JobInfo(val ticket: Ticket, val arguments: Array<Any?>) {

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as JobInfo

            if (ticket != other.ticket) return false

            return true
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
                when (val job = jobQueue.poll()) {
                    null -> return@executeIfNotEmpty
                    else -> {
                        log.debug { "Setting job with ticket '${job.ticket}' to coroutine run." }
                        activeJobMap[job.ticket] = DeferredJobResult(
                            job.ticket,
                            DateTime.now().millis,
                            CoroutineScope(dispatcher).async { futureCall.call(*job.arguments) })
                    }
                }
            }
        }
    }

    /**
     * Submit and wait for result
     */
    fun submitAndAwait(arguments: Array<Any?>, timeout: Long): T {
        return await(submit(arguments), timeout)
    }


    /**
     * Submit a job to scheduled execution, e.g queue.
     */
    private fun submit(arguments: Array<Any?>): Ticket {
        val ticket = runningTicket.incrementAndGet()
        jobQueue.add(JobInfo(ticket, arguments))
        return ticket
    }


    private fun inQueue(ticket: Ticket): Boolean {
        return jobQueue.contains(JobInfo(ticket, emptyArray()))
    }


    /**
     * Wait for job result.
     *
     * Presumes that ticket is valid.
     *
     * It is not exposed as knowing job ticket, any job and therefore any job result could be retrieved.
     */
    private fun await(ticket: Ticket, timeout: Long): T {
        val endTime = DateTime.now().millis + timeout

        try {
            while (inQueue(ticket)) {
                log.debug { "Requested job by the ticket '$ticket' ---> in queue." }

                if (DateTime.now().millis > endTime) {
                    throw AwaitTimeoutException(
                        "Scheduled job has been cancelled due to timeout. Reason: long wait in queue.",
                        ReqError.ASSESSMENT_AWAIT_TIMEOUT
                    )
                }
                Thread.sleep(1000)
            }

            log.debug { "Requested job by the ticket '$ticket' ---> deferred result map" }
            return runBlocking {
                val deferred = activeJobMap.remove(ticket)?.result?.await() ?: throw AwaitTimeoutException(
                    "Scheduled job has been cancelled due to timeout. Reason: long running time.",
                    ReqError.ASSESSMENT_AWAIT_TIMEOUT
                )

                log.debug { "Requested job by the ticket '$ticket' ---> executed: $deferred." }
                deferred
            }

        } catch (ex: CancellationException) {
            throw AwaitTimeoutException(
                "Scheduled job has been cancelled due to timeout. Reason: long running time.",
                ReqError.ASSESSMENT_AWAIT_TIMEOUT
            )
        } finally {
            jobQueue.remove(JobInfo(ticket, emptyArray()))
            activeJobMap.remove(ticket)
        }
    }

    /**
     * Return number of jobs scheduled to run or which are already running.
     */
    fun countActive(): Long {
        return activeJobMap.values.sumOf { if (it.result.isActive) 1L else 0L }
    }

    /**
     * Return number of jobs scheduled to run or which are already running.
     */
    fun countQueue(): Long {
        return jobQueue.size.toLong()
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
        activeJobMap.values.removeIf {
            val remove = it.submitted + timeout < currentTime
            if (remove) {
                it.result.cancel()
                removed++
            }
            remove
        }
        return removed
    }
}
