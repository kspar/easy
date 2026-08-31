package core.ems.service

import com.fasterxml.jackson.annotation.JsonProperty
import core.ems.service.cache.CachingService
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.Valid
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.ConcurrentLinkedQueue

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class StatisticsController(private val statisticsService: StatisticsService) {

    /**
     * Three aggregate counts, readable with no account.
     *
     * Public because the landing page is the first thing a visitor who has never logged in sees, and
     * it has shown these counts all along — what it did not have was permission. The endpoint was
     * `@Secured` for the three roles, so every anonymous visit sat in a five-second retry loop
     * against a 401 for as long as the tab stayed open, writing two lines into core's log per
     * attempt. See the note beside this path in `PERMIT_ALL_PATTERNS` for why publishing three
     * counts is a different decision from publishing component versions.
     *
     * ### Why a POST, and why the request body is the response type
     *
     * This is a long poll, and the body is the caller saying *what it already has*. Matching stats
     * mean there is nothing to send yet, so the request is held until they change — that is how the
     * counters on the page move without the client polling on a timer. A caller with nothing yet
     * sends no body at all and is answered immediately.
     *
     * So the client posts back, verbatim, a body core previously serialised — which makes
     * [StatResp]'s wire names load-bearing in *both* directions. `StatisticsApiTest` posts a
     * received body back for exactly that reason: get the names wrong on one side and every request
     * after the first is a 400, which the client would see only as a poll that never updates.
     */
    @PostMapping("/unauth/statistics/common")
    fun controller(@Valid @RequestBody dto: StatResp?): StatResp {
        log.debug { "Public statistics query" }
        return runBlocking { statisticsService.getOrWaitStatUpdate(dto) }
    }
}

data class StatResp(
    @get:JsonProperty("in_auto_assessing") val inAutoAssessing: Long,
    @get:JsonProperty("total_submissions") val totalSubmissions: Long,
    @get:JsonProperty("total_users") val totalUsers: Long
)


@Service
class StatisticsService(
    private val cachingService: CachingService,
    /**
     * How long a long poll is held before being answered with what we already have.
     *
     * Defaulted rather than added to `application.yaml.sample` and the ansible template: a bound
     * that exists to stop a thread being held forever is not something an operator needs to tune,
     * and `SampleConfigCompletenessTest` deliberately ignores defaulted placeholders so that the
     * sample lists necessities rather than every knob.
     */
    @Value("\${easy.core.statistics.long-poll-timeout.ms:30000}") private val longPollTimeoutMs: Long,
    /**
     * How many callers may be waiting at once. Past this, a caller is answered immediately.
     *
     * The real bound on what long-polling can take from Tomcat's thread pool — see
     * [getOrWaitStatUpdate], where the reasoning is, along with why the timeout is not that bound.
     * Defaulted for the same reason as [longPollTimeoutMs].
     */
    @Value("\${easy.core.statistics.max-waiting-clients:50}") private val maxWaitingClients: Int,
) {
    private val clientsListening = ConcurrentLinkedQueue<Channel<StatResp>>()
    private var lastKnownResponse = composeStatResp()

    /**
     * The caller's stats if they are stale, otherwise a wait — bounded twice over — for an update.
     *
     * ### Waiting occupies a request thread, and the timeout is not what bounds that
     *
     * `runBlocking` here runs on the Tomcat request thread, so a waiting caller holds that thread
     * for the duration. [longPollTimeoutMs] bounds any single request and gives us a moment to
     * notice the caller has gone, but it does **not** bound a client's occupancy: the client
     * re-posts the instant it gets an answer, and a timed-out hold is indistinguishable from an
     * update, so one client sits on close to a whole thread either way.
     *
     * [maxWaitingClients] is the bound that matters. Past it a caller is answered immediately with
     * what we have rather than joining the queue, so long-polling can occupy at most that many of
     * Tomcat's threads however many clients ask — and the rest of the pool stays available to every
     * other endpoint. That is stricter than this code managed while the endpoint was authenticated,
     * when both the queue and the wait were unbounded; it is also the reason publishing the endpoint
     * is not handing anyone a lever. A caller turned away simply polls, which is why the client
     * keeps a floor delay between rounds.
     *
     * ### Leaving the queue
     *
     * The removal is under the same lock [pushStatUpdate] holds, and has to be: that method walks
     * this queue with an emptiness check followed by a `poll()`, and a removal from outside the lock
     * can empty it in between. `ConcurrentLinkedQueue.poll()` is a platform type, so the null would
     * land as an NPE inside a scheduled task rather than anywhere that explains itself.
     *
     * Removing here also closes an older leak: a client that disconnected mid-poll left its channel
     * in the queue forever, because nothing tells a blocked `receive()` that the socket went away.
     *
     * A push landing in the gap between the timeout firing and the removal is not lost work — the
     * value pushed *is* [lastKnownResponse], which is what this then returns.
     */
    suspend fun getOrWaitStatUpdate(currentStatResp: StatResp?): StatResp {
        val channel = Channel<StatResp>(Channel.CONFLATED)

        synchronized(this) {
            // if client has no current stats or stats differ, return latest queried stats...
            if (currentStatResp != lastKnownResponse) return lastKnownResponse
            // ...and likewise if too many are already waiting, rather than taking another thread.
            if (clientsListening.size >= maxWaitingClients) return lastKnownResponse
            clientsListening.add(channel)
        }
        // else wait for update, for as long as we are willing to hold the thread...
        withTimeoutOrNull(longPollTimeoutMs) { channel.receive() }?.let { return it }

        return synchronized(this) {
            clientsListening.remove(channel)
            lastKnownResponse
        }
    }


    @Scheduled(fixedDelayString = "\${easy.core.statistics.fixed-delay.ms}")
    fun pushStatUpdate() {
        synchronized(this) {
            val databaseState = composeStatResp()

            // If nothing new to push, don't push, just return
            if (databaseState == lastKnownResponse) return

            // Has new state, update and push
            lastKnownResponse = databaseState
            // Push response to clients. Null-safe rather than isNotEmpty()/poll(): that pair is
            // atomic only while every removal from the queue is under this lock, which is a property
            // of code elsewhere in this file rather than of this line.
            while (true) {
                val listener = clientsListening.poll() ?: break
                listener.trySend(lastKnownResponse)
            }
        }
    }

    private fun composeStatResp() = StatResp(
        cachingService.countSubmissionsInAutoAssessment(),
        cachingService.countSubmissions(),
        cachingService.countTotalUsers()
    )
}
