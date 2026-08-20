package core.ems.service

import core.aas.AutoGradeScheduler
import core.db.ExecutorContainerImage
import core.db.Executor as ExecutorTable
import core.testing.Auth
import core.testing.FakeExecutor
import core.testing.Fixtures
import core.testing.HttpApi
import core.testing.IntegrationTest
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import java.util.concurrent.atomic.AtomicInteger

/**
 * `GET /v2/versions` — what is deployed, and who may ask.
 *
 * These assertions lived in `PublicSurfaceTest` until EZ-1782, because the endpoint was
 * unauthenticated. It is teacher-and-admin now, so they moved here rather than being deleted: what
 * the payload contains and what it refuses to contain is worth the same amount either way, and the
 * one thing that genuinely changed — who gets a 200 — is asserted below for every role.
 *
 * The base-URL assertion is the one to keep whatever else changes. An executor's address is an
 * internal endpoint that runs arbitrary submitted code, and the two endpoints that read those rows
 * read the same rows — so widening this DTO by one field is a one-line change with no other symptom.
 * A teacher has no more business knowing that address than a stranger does.
 */
@IntegrationTest
class VersionsApiTest(
    @Autowired mockMvc: MockMvc,
    @Autowired private val scheduler: AutoGradeScheduler,
    @Autowired private val versions: VersionsService,
) {

    private val api = HttpApi(mockMvc)

    private lateinit var executor: FakeExecutor

    private companion object {
        val counter = AtomicInteger()
    }

    @BeforeEach
    fun populate() {
        // A version unique to this test, so an assertion about it cannot be satisfied by another
        // test's cached snapshot.
        executor = FakeExecutor(
            version = "aae-${counter.incrementAndGet()}",
            // Disagreeing with itself on purpose: declared 1.7.11, installed 1.7.4. That is the real
            // August 2026 state, and a fixture where the two matched would not prove the payload can
            // express a disagreement at all — which is the only reason both fields exist.
            gradingImagesJson = """
                [{"name": "silmused", "created_at": "2026-08-12T11:14:00Z", "source": "label",
                  "libraries": [{"name": "silmused", "declared": "1.7.11", "installed": "1.7.4"}]}]
            """.trimIndent(),
        )

        // VersionsService caches for five minutes in a plain field that survives both the database
        // truncation and the Spring cache invalidation the reset extension does. Without this the
        // first test to ask decides what every later one sees.
        versions.clearCache()

        transaction {
            Fixtures.teacher(Auth.TEACHER_ID)
            Fixtures.student(Auth.STUDENT_ID)
            Fixtures.admin(Auth.ADMIN_ID)
            Fixtures.executor("test", executor.baseUrl)
        }
        scheduler.syncExecutorsFromDB()
    }

    @AfterEach
    fun stopExecutor() {
        executor.close()
        transaction {
            ExecutorContainerImage.deleteAll()
            ExecutorTable.deleteAll()
        }
        scheduler.syncExecutorsFromDB()
    }

    // --- who may ask ----------------------------------------------------------------------------

    @Test
    fun `a teacher can read the versions`() {
        val resp = api.get("/v2/versions", Auth.asTeacher())
        assertEquals(200, resp.status)

        val core = resp.jsonOrNull!!.get("core")
        assertTrue(core.get("version").asString().isNotBlank())
        assertTrue(core.get("commit").asString().isNotBlank())
        assertEquals(executor.version, resp.elements("executors").single().get("version").asString())
    }

    @Test
    fun `an admin can read the versions`() {
        val resp = api.get("/v2/versions", Auth.asAdmin())
        assertEquals(200, resp.status)
        // Asserted against the executor this test started, in both role tests, for the clearCache
        // reason above: whichever ran first would otherwise decide what the other one sees, and with
        // a near-identical fixture that reads as a pass.
        assertEquals(executor.version, resp.elements("executors").single().get("version").asString())
    }

    @Test
    fun `a student cannot`() {
        // The change EZ-1782 actually made. A student is signed in and still refused, so this is not
        // merely testing that authentication happens.
        assertEquals(403, api.get("/v2/versions", Auth.asStudent()).status)
    }

    @Test
    fun `an anonymous caller cannot`() {
        assertEquals(401, api.get("/v2/versions", api.anonymous()).status)
    }

    @Test
    fun `the old unauthenticated path is gone rather than still answering`() {
        // Leaving `/unauth/versions` routed would have kept the whole payload public while the new
        // path looked protected, which is the worst of both.
        val resp = api.get("/v2/unauth/versions", api.anonymous())
        assertTrue(resp.status == 404 || resp.status == 401) { "got ${resp.status}" }
    }

    // --- what it says ---------------------------------------------------------------------------

    @Test
    fun `it names executors but never their base urls`() {
        val resp = api.get("/v2/versions", Auth.asTeacher())

        val executors = resp.elements("executors")
        assertEquals(1, executors.size)
        assertEquals("test", executors[0].get("name").asString())
        assertTrue(executors[0].get("reachable").asBoolean())

        listOf("base_url", "baseUrl", "url").forEach {
            assertFalse(executors[0].has(it)) { "The version endpoint published an executor's '$it'" }
        }
        assertFalse(resp.body.contains(executor.baseUrl)) { "An executor's address is in the payload" }

        // Grading images carry names and versions only. A digest or a size would be a fact about the
        // host rather than about what it grades with.
        listOf("digest", "repo_digests", "size", "id", "path").forEach {
            assertFalse(executors[0].has(it)) { "The version endpoint published an executor's '$it'" }
        }
    }

    @Test
    fun `grading image versions are reported, declared and installed separately`() {
        val resp = api.get("/v2/versions", Auth.asTeacher())

        val images = resp.elements("executors").single().get("grading_images")
        assertTrue(images.isArray) { "grading_images must always be an array, never null" }

        val silmused = images.toList().single { it.get("name").asString() == "silmused" }
        val library = silmused.get("libraries").toList().single()
        assertEquals("silmused", library.get("name").asString())
        assertEquals("1.7.11", library.get("declared").asString())
        assertEquals("1.7.4", library.get("installed").asString())
    }
}
