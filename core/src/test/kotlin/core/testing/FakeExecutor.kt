package core.testing

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * An executor, for tests. Answers `POST /v1/grade` the way `aae` does.
 *
 * ### Why this exists rather than a mocked `AutoGradeScheduler`
 *
 * `doc/testing.md` calls submission → grading → feedback "the application's central promise" and
 * then places it on a deployed environment, on the assumption that exercising it needs Docker and a
 * real executor. It does not. What core does with a grading result crosses a coroutine, a scheduler,
 * a `RestTemplate`, Jackson, three database writes and a status machine — and **every one of those
 * is on this side of the wire**. The only thing on the far side is a container running Python, which
 * `aae`'s own tests are for.
 *
 * So the executor is 40 lines of `com.sun.net.httpserver`, which is in the JDK: no new dependency,
 * no Docker, and the path under test is the production one all the way down to the socket.
 *
 * ### Loopback, and a port the OS chose
 *
 * Bound to 127.0.0.1 explicitly and to port 0, so nothing is reachable from outside the machine and
 * two runs on one host cannot collide. The base URL is read back from the server after it starts,
 * never guessed.
 *
 * Usage: start one, put its [baseUrl] in an `executor` row, and set [respond] to whatever the test
 * needs. [requests] records what core actually sent, which is the half of an integration test that a
 * status assertion cannot reach — a grading request that carried the wrong solution would still
 * produce a perfectly good grade.
 */
class FakeExecutor(
    val version: String = "test-executor",
    /**
     * What `/v1/version` reports for `grading_images`, as raw JSON (EZ-1781).
     *
     * Defaulted, so every existing call site is unchanged and keeps describing an executor that
     * reports no images — which is also what a real executor running an older aae does, and
     * therefore the case most worth having as the default.
     */
    val gradingImagesJson: String = "[]",
) : AutoCloseable {

    /** What a `/v1/grade` call does. Replace per test; the default grades everything 100. */
    sealed interface Behaviour {
        /** The ordinary answer: `{"grade": …, "feedback": …}`. */
        data class Grade(val grade: Int, val feedback: String = "OK") : Behaviour

        /** An executor that is up but broken — `callExecutor` turns this into an `ExecutorException`. */
        data class Fail(val status: Int = 500, val body: String = """{"error":"boom"}""") : Behaviour

        /** A body core cannot parse into `ExecutorResponse`. */
        data class Garbage(val body: String) : Behaviour

        /** Never answers, until the server is closed. Stands in for an executor that has hung. */
        data object Hang : Behaviour
    }

    /** A request core sent, as it arrived on the wire. */
    data class Request(val path: String, val body: String)

    private val behaviour = AtomicReference<Behaviour>(Behaviour.Grade(100))
    private val received = ConcurrentLinkedQueue<Request>()

    private val server: HttpServer = HttpServer.create(
        // Loopback explicitly, not the wildcard address a bare port would bind.
        InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0),
        0,
    ).apply {
        executor = Executors.newFixedThreadPool(4)
        createContext("/v1/grade", ::handleGrade)
        createContext("/v1/version", ::handleVersion)
        start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    /** Every `/v1/grade` core has sent, oldest first. */
    val requests: List<Request> get() = received.toList()

    fun respond(with: Behaviour) = behaviour.set(with)

    fun reset() {
        received.clear()
        behaviour.set(Behaviour.Grade(100))
    }

    private fun handleGrade(exchange: HttpExchange) {
        val body = exchange.requestBody.use { it.readBytes().decodeToString() }
        received += Request(exchange.requestURI.path, body)

        when (val current = behaviour.get()) {
            is Behaviour.Grade -> send(
                exchange, 200,
                """{"grade": ${current.grade}, "feedback": ${quote(current.feedback)}}""",
            )

            is Behaviour.Fail -> send(exchange, current.status, current.body)
            is Behaviour.Garbage -> send(exchange, 200, current.body)
            // Deliberately no response and no close. The exchange is abandoned; closing the server
            // at the end of the test releases it. A sleep here would make the test wait for it.
            Behaviour.Hang -> Unit
        }
    }

    private fun handleVersion(exchange: HttpExchange) = send(
        exchange, 200,
        """{"version": ${quote(version)}, "commit": "abc1234", "built_at": null, """ +
            """"grading_images": $gradingImagesJson}""",
    )

    private fun send(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /**
     * Stop the listener **and** the thread pool.
     *
     * `HttpServer.stop` explicitly does not shut down an executor the caller supplied — it closes
     * the listener and stops dispatching, and the pool stays alive. One of these is built per test
     * method in two classes, so without the second line a full run ends with a live pool per test,
     * of non-daemon threads, for the rest of the JVM's life. That is the classic reason a Gradle
     * test worker finishes its tests and then hangs.
     */
    override fun close() {
        server.stop(0)
        (server.executor as? ExecutorService)?.shutdownNow()
    }
}
