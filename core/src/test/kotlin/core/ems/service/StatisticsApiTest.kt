package core.ems.service

import core.testing.Auth
import core.testing.HttpApi
import core.testing.IntegrationTest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import java.time.Duration
import kotlin.system.measureTimeMillis

/**
 * `POST /v2/unauth/statistics/common` — the landing page's three counts, and the long poll they
 * arrive on.
 *
 * Public, so the shape assertion below is a security assertion: this payload goes to anyone who asks,
 * and the only thing keeping it to three aggregates is that [StatResp] has three fields. The same
 * argument as `VersionsApiTest`'s base-URL leg, one step further out — there is no caller here at all.
 *
 * ### The two failures this exists to catch
 *
 * **The endpoint stops being public.** It was `@Secured` for the three roles while the landing page
 * called it on every anonymous visit, so each visitor's tab sat in a five-second retry loop against
 * a 401 for as long as it stayed open, two lines in core's log per attempt. That went unnoticed for
 * as long as it did because nothing about it fails — the page shows a spinner and the server looks
 * busy.
 *
 * **The round trip breaks.** The client posts back a body core serialised, so the wire names have to
 * work in both directions off one set of `@get:JsonProperty` annotations. If they ever do not, every
 * request after the first is a 400 and the counters simply never move; `DtoWireNamesTest` checks the
 * names are declared and snake_case, which is a different question from whether Jackson can read
 * them back.
 */
@IntegrationTest
class StatisticsApiTest(
    @Autowired mockMvc: MockMvc,
    @Autowired private val statistics: StatisticsService,
) {

    private val api = HttpApi(mockMvc)

    private val path = "/v2/unauth/statistics/common"

    private companion object {
        /** The wire names `web/src/api/statistics.ts` reads, and the whole of the payload. */
        val FIELDS = listOf("in_auto_assessing", "total_submissions", "total_users")

        /**
         * `easy.core.statistics.max-waiting-clients` as `src/test/resources/application.yaml` sets it.
         *
         * Duplicated from the config on purpose — the alternative is injecting the value and asserting
         * it against itself. Divergence fails loudly rather than quietly: a caller this expects to be
         * turned away would instead wait out the long-poll timeout.
         */
        const val WAITING_CAP = 2
    }

    @Test
    fun `the counts are readable with no account`() {
        val resp = api.post(path, caller = api.anonymous())
        assertEquals(200, resp.status) { resp.body }

        val body = resp.jsonOrNull!!
        // By name, because a rename is silent on both sides: the client would read `undefined` and
        // render a zero, which looks like a quiet Tuesday rather than a bug.
        FIELDS.forEach {
            assertTrue(body.has(it)) { "No `$it` in the public statistics payload: $body" }
        }

        // And nothing else. On an endpoint with no caller, "what else is in here" is the whole
        // review, and a field added to StatResp would otherwise reach the internet unremarked.
        assertEquals(3, body.size()) { "The public statistics payload grew a field: $body" }
    }

    /**
     * Signing in changes nothing about it — the handler takes no caller, so there is nothing here
     * that could be per-caller.
     *
     * Field names rather than values: the counts are read from a cache that refreshes on a timer,
     * so two calls a moment apart are not guaranteed to agree on a number, and a test that demanded
     * they did would fail for a reason that has nothing to do with what it is about.
     */
    @Test
    fun `a signed-in caller gets the same payload`() {
        val anonymous = api.post(path, caller = api.anonymous())
        val student = api.post(path, caller = Auth.asStudent())

        assertEquals(200, student.status) { student.body }
        assertEquals(anonymous.jsonOrNull!!.size(), student.jsonOrNull!!.size()) { student.body }
        FIELDS.forEach {
            assertTrue(student.jsonOrNull!!.has(it)) { "No `$it` for a signed-in caller: ${student.body}" }
        }
    }

    /**
     * The second request of a real poll: the exact bytes core just sent, posted straight back.
     *
     * A 400 here is the round-trip failure above. A hang is the missing bound below. Both are
     * covered by asking for the one thing a client needs — that a well-formed second request is
     * answered at all.
     */
    @Test
    fun `posting back the body core just sent is answered, not refused`() {
        val first = api.post(path, caller = api.anonymous())
        assertEquals(200, first.status) { first.body }

        // `ThrowingSupplier` spelled out: Kotlin resolves a bare lambda here to the `Executable`
        // overload, whose result is Unit, and the assertions below then have nothing to read.
        val second = assertTimeoutPreemptively(
            Duration.ofSeconds(20),
            ThrowingSupplier { api.post(path, first.body, api.anonymous()) },
        )
        assertEquals(200, second.status) { second.body }
        assertEquals(3, second.jsonOrNull!!.size()) { second.body }
    }

    /**
     * A caller whose stats already match is answered eventually, rather than held forever.
     *
     * `getOrWaitStatUpdate` blocks the request thread — `runBlocking` on the Tomcat thread — and
     * before EZ-1844 it blocked with no timeout, so a caller with current stats held a thread until
     * the counts happened to change. On an idle evening that is indefinite, and now that the
     * endpoint takes no account it is indefinite for anyone who asks.
     *
     * Asserted against the service rather than through MockMvc so the wait is the only thing in the
     * frame. Nothing here changes a count, so the first call's answer is exactly what the second
     * call will be told it already has — which is the case that waits. If some concurrent change did
     * push an update, this passes early; the only way to fail it is not to return, which is the
     * failure being guarded.
     */
    @Test
    fun `the long poll wait is bounded`() {
        val current = runBlocking { statistics.getOrWaitStatUpdate(null) }

        assertTimeoutPreemptively(Duration.ofSeconds(20)) {
            runBlocking { statistics.getOrWaitStatUpdate(current) }
        }
    }

    /**
     * Past the cap, a caller is answered rather than queued.
     *
     * This is the bound that actually protects the thread pool — the timeout caps one request, but
     * the client re-posts the moment it is answered, so without a cap on *how many* may wait, an
     * endpoint that needs no account is a lever on every Tomcat thread. The service KDoc has the
     * full argument.
     *
     * The timing comparison is deliberately a wide one rather than a tight measurement: a queued
     * caller waits the whole 1500ms the test config allows, an unqueued one never suspends, so
     * anything under half of that can only be the second case. The [delay] is to let the waiters
     * reach the queue first — the assertion is about a full queue, not about a race to fill one.
     *
     * Honest limitation: if a count changed while this ran, the waiters would be released early, the
     * queue would not be full, and this would pass without having tested anything. It cannot fail
     * for that reason, and nothing here writes to the database.
     */
    @Test
    fun `past the waiting cap a caller is answered immediately`() {
        runBlocking {
            val current = statistics.getOrWaitStatUpdate(null)

            val waiters = List(WAITING_CAP) { async { statistics.getOrWaitStatUpdate(current) } }
            delay(200)

            val elapsed = measureTimeMillis { statistics.getOrWaitStatUpdate(current) }
            assertTrue(elapsed < 750) {
                "A caller arriving past the cap of $WAITING_CAP waited ${elapsed}ms, so it was queued anyway"
            }

            waiters.awaitAll()
        }
    }
}
