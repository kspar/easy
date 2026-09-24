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
 * An Anthropic Messages API, for tests. Answers `POST /v1/messages` the way the real one does.
 *
 * Same idea as [FakeExecutor] and for the same reason: everything core does with a model's answer —
 * building the prompt, the HTTP call, parsing, the markdown render, the audit row, the feed — is on
 * this side of the wire, and a test that mocked the provider interface would skip the half of it
 * most likely to be wrong (the wire format). The one thing on the far side is a language model,
 * which no test should be paying for.
 *
 * Loopback, port 0, base URL read back after start. Put [baseUrl] in a course's `ai_base_url` and
 * the provider will come here with the course's key in `x-api-key`.
 */
class FakeAnthropic : AutoCloseable {

    sealed interface Behaviour {
        /** A normal answer with one text block. */
        data class Answer(
            val text: String,
            val model: String = "claude-test",
            val inputTokens: Int = 321,
            val outputTokens: Int = 45,
            val stopReason: String = "end_turn",
        ) : Behaviour

        /** HTTP 200 with `stop_reason: refusal` and no text — what a safety classifier produces. */
        data object Refusal : Behaviour

        /** An HTTP error with the vendor's error body. */
        data class Fail(val status: Int = 500, val body: String = """{"type":"error","error":{"type":"api_error","message":"boom"}}""") : Behaviour
    }

    /** A request core sent: the headers it needs to have sent, and the body as it arrived. */
    data class Request(val apiKey: String?, val version: String?, val body: String)

    private val behaviour = AtomicReference<Behaviour>(Behaviour.Answer("The loop stops one step early."))
    private val received = ConcurrentLinkedQueue<Request>()

    private val server: HttpServer = HttpServer.create(
        InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0,
    ).apply {
        executor = Executors.newFixedThreadPool(2)
        createContext("/v1/messages", ::handle)
        start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    val requests: List<Request> get() = received.toList()

    fun respond(with: Behaviour) = behaviour.set(with)

    fun reset() {
        received.clear()
        behaviour.set(Behaviour.Answer("The loop stops one step early."))
    }

    private fun handle(exchange: HttpExchange) {
        val body = exchange.requestBody.use { it.readBytes().decodeToString() }
        received += Request(
            exchange.requestHeaders.getFirst("x-api-key"),
            exchange.requestHeaders.getFirst("anthropic-version"),
            body,
        )

        when (val current = behaviour.get()) {
            is Behaviour.Answer -> send(
                exchange, 200,
                """{"id":"msg_test","type":"message","role":"assistant","model":${quote(current.model)},""" +
                        """"content":[{"type":"text","text":${quote(current.text)}}],""" +
                        """"stop_reason":${quote(current.stopReason)},"stop_sequence":null,""" +
                        """"usage":{"input_tokens":${current.inputTokens},"output_tokens":${current.outputTokens}}}""",
            )

            Behaviour.Refusal -> send(
                exchange, 200,
                """{"id":"msg_test","type":"message","role":"assistant","model":"claude-test",""" +
                        """"content":[],"stop_reason":"refusal","stop_sequence":null,""" +
                        """"usage":{"input_tokens":10,"output_tokens":0}}""",
            )

            is Behaviour.Fail -> send(exchange, current.status, current.body)
        }
    }

    private fun send(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    /** See [FakeExecutor.close]: the pool has to go too, or the test worker never exits. */
    override fun close() {
        server.stop(0)
        (server.executor as? ExecutorService)?.shutdownNow()
    }
}
