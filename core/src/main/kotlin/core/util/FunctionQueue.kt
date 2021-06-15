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

class FunctionQueue<T>(private val futureCall: KFunction<T>, private val dispatcher: CoroutineDispatcher) {
    private var runningTicket = AtomicLong(0)

    /**
     * Holds [JobInfo] that are not yet assigned to coroutine execution. Chose [ConcurrentLinkedQueue] as it has no
     * fixed size limit.
     */
    private val pendingJobs = ConcurrentLinkedQueue<JobInfo>()

    /**
     * Holds [JobInfo] assigned to coroutine. The finished coroutine results will be made available in this map.
     */
    private val assignedJobs = ConcurrentHashMap<Ticket, DeferredOutput<T>>()

    /**
     * @param ticket uniq ID that identifies job.
     * @param submitted time in long, which the job was submitted via [submit]. Expected to equal [JobInfo] param.
     * @param deferred represents the future function/job call
     */
    private data class DeferredOutput<E>(val ticket: Ticket, val submitted: Long, val deferred: Deferred<E>)

    /**
     *
     * @param ticket job ticket that identifies scheduled job.
     * @param submitted time in long, which the job was submitted via [submit]. Should be same in [DeferredOutput].
     * @param arguments to be used on [futureCall].
     *
     */
    private data class JobInfo(val ticket: Ticket, val submitted: Long, val arguments: Array<Any?>) {

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
        run executeIfNotEmpty@{
            repeat(n) {
                when (val job = pendingJobs.poll()) {
                    null -> return@executeIfNotEmpty
                    else -> {
                        log.debug { "Setting job with ticket '${job.ticket}' to coroutine run." }
                        assignedJobs[job.ticket] = DeferredOutput(
                            job.ticket,
                            job.submitted,
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
     * @return [futureCall] output
     */
    fun submitAndAwait(arguments: Array<Any?>, timeout: Long): T {
        return await(submit(arguments), timeout)
    }


    /**
     * Submit a job to scheduled execution, e.g into [pendingJobs].
     */
    private fun submit(arguments: Array<Any?>): Ticket {
        val ticket = runningTicket.incrementAndGet()
        pendingJobs.add(JobInfo(ticket, DateTime.now().millis, arguments))
        return ticket
    }


    /**
     * Job in [pendingJobs], e.g. not yet called with coroutine via [executeN]?
     */
    private fun isPending(ticket: Ticket): Boolean {
        return pendingJobs.contains(JobInfo(ticket, 0L, emptyArray()))
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
     * Get result from [assignedJobs].
     */
    private fun waitResult(ticket: Ticket): T {
        return runBlocking {
            val callResult = assignedJobs.remove(ticket)?.deferred?.await()

            /**
            This is null if:
            1. Job is put to the [assignedJobs], but is removed by [clearOlder] due to the timeout.

            Also considered, but should not be possible:
            1. Job is never put to the [assignedJobs]. Could be if job is still in the [pendingJobs]. However [await] checks it.
            2. The function used actually returns null? Currently not be possible due to [futureCall] type.

            Therefore, timeout is the case for null.
             */

            if (callResult == null) throwTimeOut("Job reached maximum allowed running time.")
            callResult!!
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
                    isTimeOut(endTime) -> throwTimeOut("Timeout in queue")
                    isPending(ticket) -> Thread.sleep(1000)
                    else -> return waitResult(ticket)
                }
            }

        } catch (ex: CancellationException) {
            throwTimeOut("Job was cancelled")
        } finally {
            pendingJobs.remove(JobInfo(ticket, 0L, emptyArray()))
            assignedJobs.remove(ticket)
        }

        // Should never reach here.
        throw RuntimeException()
    }

    /**
     * Return number of jobs scheduled to run or which are already running.
     */
    fun countActive(): Long {
        return assignedJobs.values.sumOf { if (it.deferred.isActive) 1L else 0L }
    }

    /**
     * Clear job results, which were not retrieved or are still running.
     *
     * @param timeout in ms after which job is cancelled
     * @return number of old jobs that were set to timeout
     */
    @Synchronized
    fun clearOlder(timeout: Long): Long {
        // Is synchronized as ConcurrentHashMap states: iterators are designed to be used by only one thread at a time.
        var removed = 0L
        val currentTime = DateTime.now().millis

        assignedJobs.values.removeIf {
            val remove = it.submitted + timeout < currentTime
            if (remove) {
                it.deferred.cancel()
                removed++
            }
            remove
        }

        // Also remove old from pending queue because the queue can get long
        pendingJobs.removeIf {
            val remove = it.submitted + timeout < currentTime
            if (remove) {
                removed++
            }
            remove
        }
        return removed
    }
}
