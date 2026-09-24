package core.ems.service.ai

import core.db.AiProviderType
import core.db.Course
import core.testing.Auth
import core.testing.Fixtures
import core.testing.HttpApi
import core.testing.IntegrationTest
import core.testing.TestClock
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc

/**
 * `GET`/`PUT /v2/courses/{courseId}/ai`. Mostly about the one property that matters: the key goes
 * in and never comes out.
 */
@IntegrationTest
class CourseAiPropsApiTest(@Autowired mockMvc: MockMvc) {

    private val api = HttpApi(mockMvc)

    private val teacher = Auth.TEACHER_ID
    private val outsider = "teacher-elsewhere"
    private val student = Auth.STUDENT_ID

    private var courseId = 0L

    @BeforeEach
    fun populate() {
        TestClock.reset()
        transaction {
            Fixtures.teacher(teacher)
            Fixtures.teacher(outsider)
            Fixtures.student(student)
            Fixtures.admin(Auth.ADMIN_ID)
            courseId = Fixtures.course("Programming")
            Fixtures.enrolTeacher(courseId, teacher)
        }
    }

    private fun read(caller: String = teacher) = api.get("/v2/courses/$courseId/ai", Auth.asTeacher(caller))

    private fun write(props: Map<String, Any?>?, caller: String = teacher) = api.put(
        "/v2/courses/$courseId/ai", api.body("ai_props" to props), Auth.asTeacher(caller)
    )

    private fun writeAsAdmin(props: Map<String, Any?>?) = api.put(
        "/v2/courses/$courseId/ai", api.body("ai_props" to props), Auth.asAdmin()
    )

    private fun storedKey(): String? = transaction {
        Course.select(Course.aiApiKey).where { Course.id eq courseId }.single()[Course.aiApiKey]
    }

    private fun storedBaseUrl(): String? = transaction {
        Course.select(Course.aiBaseUrl).where { Course.id eq courseId }.single()[Course.aiBaseUrl]
    }

    @Test
    fun `unconfigured reads as null`() {
        val resp = read()
        assertEquals(200, resp.status) { resp.body }
        assertNull(resp.jsonOrNull?.get("ai_props")?.takeUnless { it.isNull })
    }

    @Test
    fun `the key goes in and only its tail comes back`() {
        val put = write(mapOf("provider" to "ANTHROPIC", "model" to "claude-opus-5", "api_key" to "sk-ant-secret-9876"))
        assertEquals(200, put.status) { put.body }

        val resp = read()
        val props = resp.jsonOrNull!!.get("ai_props")
        assertEquals("ANTHROPIC", props.get("provider").asString())
        assertEquals("claude-opus-5", props.get("model").asString())
        assertEquals(true, props.get("api_key_configured").asBoolean())
        assertEquals("9876", props.get("api_key_hint").asString())
        assertFalse(resp.body.contains("sk-ant-secret")) { "The key came back: ${resp.body}" }
        assertEquals("sk-ant-secret-9876", storedKey())
    }

    @Test
    fun `a write without a key keeps the stored one`() {
        write(mapOf("provider" to "ANTHROPIC", "model" to "claude-opus-5", "api_key" to "sk-ant-first"))

        val edit = write(mapOf("provider" to "ANTHROPIC", "model" to "claude-sonnet-5", "api_key" to null))
        assertEquals(200, edit.status) { edit.body }

        assertEquals("sk-ant-first", storedKey())
        assertEquals("claude-sonnet-5", read().jsonOrNull!!.get("ai_props").get("model").asString())
    }

    @Test
    fun `the first write needs a key`() {
        val resp = write(mapOf("provider" to "ANTHROPIC", "model" to "claude-opus-5", "api_key" to ""))
        assertEquals("INVALID_PARAMETER_VALUE", resp.errorCode) { resp.body }
        assertNull(storedKey())
    }

    @Test
    fun `null clears everything`() {
        writeAsAdmin(mapOf("provider" to "ANTHROPIC", "model" to "claude-opus-5", "api_key" to "sk-ant-first", "base_url" to "http://localhost:9"))
        assertEquals("http://localhost:9", storedBaseUrl())

        val cleared = write(null)
        assertEquals(200, cleared.status) { cleared.body }

        assertNull(storedKey())
        assertNull(storedBaseUrl())
        assertNull(read().jsonOrNull?.get("ai_props")?.takeUnless { it.isNull })
    }

    // The base URL is where core sends a key and a student's code. A teacher on the course is
    // trusted with the key, not with pointing core at an arbitrary host from inside the network.
    @Test
    fun `only an admin can set the base URL, and only to a web address`() {
        val asTeacher = write(mapOf("provider" to "ANTHROPIC", "model" to "m", "api_key" to "k", "base_url" to "http://localhost:9"))
        assertEquals("INVALID_PARAMETER_VALUE", asTeacher.errorCode) { asTeacher.body }
        assertNull(storedKey()) { "The whole write must be refused, not just the URL dropped" }

        for (bad in listOf("ftp://x.example", "https://proxy.example/{env}", "not a url", "localhost:9")) {
            val resp = writeAsAdmin(mapOf("provider" to "ANTHROPIC", "model" to "m", "api_key" to "k", "base_url" to bad))
            assertEquals("INVALID_PARAMETER_VALUE", resp.errorCode) { "'$bad' was accepted: ${resp.body}" }
        }

        val ok = writeAsAdmin(mapOf("provider" to "ANTHROPIC", "model" to "m", "api_key" to "k", "base_url" to "https://proxy.example/v1"))
        assertEquals(200, ok.status) { ok.body }
        assertEquals("https://proxy.example/v1", storedBaseUrl())

        // A teacher's later save, with no URL in it, leaves the admin's in place.
        val teacherEdit = write(mapOf("provider" to "ANTHROPIC", "model" to "m2", "api_key" to null, "base_url" to null))
        assertEquals(200, teacherEdit.status) { teacherEdit.body }
        assertEquals("https://proxy.example/v1", storedBaseUrl())

        // An admin's save with no URL clears it.
        writeAsAdmin(mapOf("provider" to "ANTHROPIC", "model" to "m2", "api_key" to null, "base_url" to null))
        assertNull(storedBaseUrl())
    }

    @Test
    fun `a teacher on another course is refused`() {
        assertEquals(403, read(outsider).status)
        assertEquals(403, write(mapOf("provider" to "ANTHROPIC", "model" to "m", "api_key" to "k"), outsider).status)
        assertNull(storedKey())
    }

    private fun stored(): Triple<Long?, Long, Boolean> = transaction {
        val row = Course.select(Course.aiTokenBudget, Course.aiTokensUsed, Course.aiTokensResetAt)
            .where { Course.id eq courseId }.single()
        Triple(row[Course.aiTokenBudget], row[Course.aiTokensUsed], row[Course.aiTokensResetAt] != null)
    }

    @Test
    fun `the budget is written with the props and read back with the counter`() {
        val put = write(mapOf("provider" to "ANTHROPIC", "model" to "m", "api_key" to "k", "token_budget" to 250_000))
        assertEquals(200, put.status) { put.body }

        val props = read().jsonOrNull!!.get("ai_props")
        assertEquals(250_000L, props.get("token_budget").asLong())
        assertEquals(0L, props.get("tokens_used").asLong())
        assertTrue(props.get("tokens_reset_at").isNull)

        // Lifting the limit is a null, not a zero.
        write(mapOf("provider" to "ANTHROPIC", "model" to "m", "api_key" to null, "token_budget" to null))
        assertTrue(read().jsonOrNull!!.get("ai_props").get("token_budget").isNull)

        val zero = write(mapOf("provider" to "ANTHROPIC", "model" to "m", "api_key" to null, "token_budget" to 0))
        assertEquals(400, zero.status) { zero.body }
    }

    @Test
    fun `reset zeroes the counter, records when, and leaves the budget alone`() {
        write(mapOf("provider" to "ANTHROPIC", "model" to "m", "api_key" to "k", "token_budget" to 1000))
        transaction { Course.update({ Course.id eq courseId }) { it[aiTokensUsed] = 800 } }
        assertEquals(Triple<Long?, Long, Boolean>(1000, 800, false), stored())

        val resp = api.post("/v2/courses/$courseId/ai/reset-usage", caller = Auth.asTeacher(teacher))
        assertEquals(200, resp.status) { resp.body }
        assertEquals(Triple<Long?, Long, Boolean>(1000, 0, true), stored())
        assertFalse(read().jsonOrNull!!.get("ai_props").get("tokens_reset_at").isNull)

        assertEquals(403, api.post("/v2/courses/$courseId/ai/reset-usage", caller = Auth.asTeacher(outsider)).status)
        assertEquals(403, api.post("/v2/courses/$courseId/ai/reset-usage", caller = Auth.asStudent(student)).status)
    }

    @Test
    fun `switching off drops the budget but keeps the count`() {
        write(mapOf("provider" to "ANTHROPIC", "model" to "m", "api_key" to "k", "token_budget" to 1000))
        transaction { Course.update({ Course.id eq courseId }) { it[aiTokensUsed] = 800 } }
        write(null)
        assertEquals(Triple<Long?, Long, Boolean>(null, 800, false), stored())
    }

    @Test
    fun `a student is refused`() {
        assertEquals(403, api.get("/v2/courses/$courseId/ai", Auth.asStudent(student)).status)
    }
}
