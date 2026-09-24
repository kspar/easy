package core.ems.service.ai

import core.db.AiProviderType
import core.testing.FakeAnthropic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * The wire format, both directions, against a server that speaks it. No Spring context: the
 * provider is a plain class over a `RestTemplate`, and the thing under test is what it puts on
 * the socket and what it makes of what comes back.
 */
class AnthropicProviderTest {

    private val server = FakeAnthropic()
    private val mapper = jacksonObjectMapper()

    private fun provider(model: String = "claude-opus-5") = AnthropicProvider(
        AiProviderConfig(AiProviderType.ANTHROPIC, apiKey = "sk-ant-test-1234", baseUrl = server.baseUrl, model = model)
    )

    private val request = AiCompletionRequest(system = "You are a tutor.", user = "Explain this.")

    @AfterEach
    fun stop() = server.close()

    @Test
    fun `sends the key, the version and the four fields the API wants`() {
        provider().complete(request)

        val sent = server.requests.single()
        assertEquals("sk-ant-test-1234", sent.apiKey)
        assertEquals("2023-06-01", sent.version)

        val body = mapper.readTree(sent.body)
        assertEquals("claude-opus-5", body.get("model").asString())
        assertEquals(16_000, body.get("max_tokens").asInt())
        assertEquals("You are a tutor.", body.get("system").asString())
        val messages = body.get("messages").toList()
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].get("role").asString())
        assertEquals("Explain this.", messages[0].get("content").asString())
    }

    @Test
    fun `reads the text, the model it ran and the token counts`() {
        server.respond(FakeAnthropic.Behaviour.Answer("Two lines.", model = "claude-opus-5-actual", inputTokens = 900, outputTokens = 12))

        val result = provider().complete(request)

        assertEquals("Two lines.", result.text)
        assertEquals("claude-opus-5-actual", result.model)
        assertEquals(900, result.tokensIn)
        assertEquals(12, result.tokensOut)
        assertTrue(result.rawResponse.contains("msg_test")) { "rawResponse is not the body as received" }
    }

    @Test
    fun `an HTTP error becomes a provider exception that keeps the body`() {
        server.respond(FakeAnthropic.Behaviour.Fail(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""))

        val e = assertThrows<AiProviderException> { provider().complete(request) }

        assertTrue(e.message!!.contains("401")) { e.message }
        assertNotNull(e.rawResponse)
        assertTrue(e.rawResponse!!.contains("invalid x-api-key"))
    }

    @Test
    fun `a refusal is not an answer`() {
        server.respond(FakeAnthropic.Behaviour.Refusal)

        val e = assertThrows<AiProviderException> { provider().complete(request) }

        assertTrue(e.message!!.contains("refused")) { e.message }
        assertTrue(e.rawResponse!!.contains("\"refusal\""))
    }

    @Test
    fun `a base URL the client cannot use is a provider exception too`() {
        // `{env}` reads as a URI template variable to RestTemplate and blows up before any I/O.
        val broken = AnthropicProvider(
            AiProviderConfig(AiProviderType.ANTHROPIC, "k", baseUrl = "https://proxy.example/{env}", model = "m")
        )
        assertThrows<AiProviderException> { broken.complete(request) }
    }

    @Test
    fun `nothing listening is a provider exception, not a stack trace`() {
        val dead = AnthropicProvider(
            AiProviderConfig(AiProviderType.ANTHROPIC, "k", baseUrl = "http://127.0.0.1:1", model = "m")
        )
        assertThrows<AiProviderException> { dead.complete(request) }
    }
}
