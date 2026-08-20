package core.conf.security

import core.db.AnonymousSubmission
import core.db.ExecutorContainerImage
import core.db.Executor as ExecutorTable
import core.testing.Auth
import core.testing.FakeExecutor
import core.testing.Fixtures
import core.testing.HttpApi
import core.testing.IntegrationTest
import core.testing.TestClock
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.module.kotlin.jacksonObjectMapper
import core.aas.AutoGradeScheduler
import core.ems.service.VersionsService
import java.util.concurrent.atomic.AtomicInteger

/**
 * Everything reachable from the internet with no account, exercised as the internet reaches it.
 *
 * There are five patterns in `PERMIT_ALL_PATTERNS` and this is the whole surface: versions, an
 * anonymous exercise's details, an anonymous submission, a published article, an uploaded file. Two
 * of them return content whose visibility is decided by a column, and one of them **runs code**.
 *
 * ### What already exists, and what this adds
 *
 * `EndpointSecuritySurfaceTest` asserts the *list* is honest — every pattern matches a real endpoint,
 * and no pattern matches an endpoint outside its allowlist. `EndpointAuthorizationMatrixTest`
 * asserts anonymous callers get 401 everywhere else. Both are structural, and both would still pass
 * if every public endpoint returned the wrong thing.
 *
 * This is the behavioural half: what the public endpoints actually *do*, and — the part worth having
 * — what they refuse. The articles and files legs live in `ArticleApiTest` and `FileApiTest`, which
 * is where the rest of those endpoints' behaviour is; the three remaining ones are here.
 *
 * ### The rule under `assertUnauthAccessToExercise`
 *
 * One boolean on the exercise decides whether a stranger may read it and run code against it. It
 * defaults to false and is set per exercise by a teacher, so the failure mode is not "a bug appears"
 * but "a flag stops being consulted" — after which every exercise in the library is public and
 * anonymously executable, and nothing looks wrong from the inside.
 */
@IntegrationTest
class PublicSurfaceTest(
    @Autowired mockMvc: MockMvc,
    @Autowired private val scheduler: AutoGradeScheduler,
    @Autowired private val versions: VersionsService,
) {

    private val api = HttpApi(mockMvc)

    private val teacher = Auth.TEACHER_ID
    private val student = Auth.STUDENT_ID

    private lateinit var executor: FakeExecutor
    private var publicExercise = 0L
    private var privateExercise = 0L

    private companion object {
        val counter = AtomicInteger()
    }

    @BeforeEach
    fun populate() {
        TestClock.reset()
        // A version unique to this test, so an assertion about it cannot be satisfied by a previous
        // test's cached snapshot. See the note on clearCache below — that hazard is real here, and
        // an identical fixture would have hidden it.
        executor = FakeExecutor(
            version = "aae-${counter.incrementAndGet()}",
            // Deliberately disagreeing with itself: declared 1.7.11, installed 1.7.4. That is the
            // real August 2026 state, and a fixture where the two matched would not prove the
            // payload can express a disagreement at all — which is the only reason both fields exist.
            gradingImagesJson = """
                [{"name": "silmused", "created_at": "2026-08-12T11:14:00Z", "source": "label",
                  "libraries": [{"name": "silmused", "declared": "1.7.11", "installed": "1.7.4"}]}]
            """.trimIndent(),
        )

        // VersionsService caches executor versions for five minutes in a plain field, so it survives
        // both the database truncation and the Spring cache invalidation the reset extension does.
        // Without this the first test to read /unauth/versions decides what the rest of the suite
        // sees.
        versions.clearCache()

        transaction {
            Fixtures.teacher(teacher)
            Fixtures.student(student)
            publicExercise = Fixtures.autoExercise(
                "Try it yourself", teacher,
                anonymousAutoassessEnabled = true,
                anonymousAutoassessTemplate = "# write your solution here",
            )
            privateExercise = Fixtures.autoExercise("Exam question 3", teacher)
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

    // --- /unauth/versions -------------------------------------------------------------------------

    /**
     * The version endpoint is public so that whoever is reporting a bug can read it off the About
     * page — including someone who cannot log in, which is the report that needs a version most.
     */
    @Test
    fun `versions are readable with no account`() {
        val resp = api.get("/v2/unauth/versions", api.anonymous())
        assertEquals(200, resp.status)

        val core = resp.jsonOrNull!!.get("core")
        assertTrue(core.get("version").asString().isNotBlank())
        assertTrue(core.get("commit").asString().isNotBlank())

        // The executor version too, in *both* tests that read this endpoint, and each against the
        // executor this test started. `VersionsService` caches for five minutes in a plain field
        // that no reset reaches, so whichever of the two runs first would otherwise decide what the
        // other one sees — and with a shared fixture that reads as a pass. Asserting it twice is
        // what makes the missing `clearCache()` fail rather than merely be lucky.
        assertEquals(executor.version, resp.elements("executors").single().get("version").asString())
    }

    /**
     * And it is deliberately narrow: names and versions of executors, **never their base URLs**.
     *
     * An executor's base URL is an internal address that grades arbitrary submitted code. It stays
     * behind the teacher/admin-only `/executors`, and the reason this is asserted rather than
     * trusted is that the two endpoints read the same rows — so widening the DTO by one field is a
     * one-line change with no other symptom.
     */
    @Test
    fun `the version endpoint names executors but never their base urls`() {
        val resp = api.get("/v2/unauth/versions", api.anonymous())

        val executors = resp.elements("executors")
        assertEquals(1, executors.size)
        assertEquals("test", executors[0].get("name").asString())
        assertEquals(executor.version, executors[0].get("version").asString())
        assertTrue(executors[0].get("reachable").asBoolean())

        listOf("base_url", "baseUrl", "url").forEach {
            assertFalse(executors[0].has(it)) { "The version endpoint published an executor's '$it'" }
        }
        assertFalse(resp.body.contains(executor.baseUrl)) { "An executor's address is in a public payload" }

        // EZ-1781 put grading images on this payload. They are safe to publish for the same reason
        // the versions are — the image names are in this public repository, and every teacher can
        // already list them through `/v2/container-images` — but only the names and versions are.
        // A digest or a size would be a fact about the host rather than about what it grades with,
        // and the widening-by-one-field risk this test exists for applies to that DTO too.
        listOf("digest", "repo_digests", "size", "id", "path").forEach {
            assertFalse(executors[0].has(it)) { "The version endpoint published an executor's '$it'" }
        }
    }

    /**
     * Grading library versions reach the public payload, and say both what was asked for and what is
     * there.
     *
     * The two being separate is the point. `declared` is what the pins file asked for; `installed`
     * is what is in the image. They agree for anything CI built, so a disagreement means somebody's
     * belief about a host is wrong — which is the state that went unnoticed for a fortnight in
     * August 2026, when a Dockerfile said silmused 1.7.11 and the image graded with 1.7.4.
     */
    @Test
    fun `grading image versions are published, declared and installed separately`() {
        val resp = api.get("/v2/unauth/versions", api.anonymous())

        val images = resp.elements("executors").single().get("grading_images")
        assertTrue(images.isArray) { "grading_images must always be an array, never null" }

        val silmused = images.toList().single { it.get("name").asString() == "silmused" }
        val library = silmused.get("libraries").toList().single()
        assertEquals("silmused", library.get("name").asString())
        assertEquals("1.7.11", library.get("declared").asString())
        assertEquals("1.7.4", library.get("installed").asString())
    }

    // --- /unauth/exercises/{id}/anonymous/details -------------------------------------------------

    @Test
    fun `an exercise marked for anonymous use is readable with no account`() {
        val resp = api.get("/v2/unauth/exercises/$publicExercise/anonymous/details", api.anonymous())
        assertEquals(200, resp.status) { resp.body }

        val body = resp.jsonOrNull!!
        assertEquals("Try it yourself", body.get("title").asString())
        assertEquals("# write your solution here", body.get("anonymous_autoassess_template").asString())
        assertTrue(body.get("submit_allowed").asBoolean()) { "An auto-graded exercise should allow submitting" }
    }

    /**
     * And one that is not is refused — to a stranger, and to a signed-in student too.
     *
     * The second half matters more than it looks. This endpoint is outside the whole course and
     * library permission system: it holds no caller and asks only `anonymous_autoassess_enabled`. So
     * "a student cannot read an arbitrary exercise through it" is not a consequence of the student's
     * permissions — it is a consequence of that one flag, and nothing else.
     */
    @Test
    fun `an exercise not marked for anonymous use is refused to everyone through this endpoint`() {
        assertEquals(403, api.get("/v2/unauth/exercises/$privateExercise/anonymous/details", api.anonymous()).status)
        assertEquals(
            403,
            api.get("/v2/unauth/exercises/$privateExercise/anonymous/details", Auth.asStudent(student)).status,
        )
        // The exercise's own author, too: the flag is about the exercise, not about the caller.
        assertEquals(
            403,
            api.get("/v2/unauth/exercises/$privateExercise/anonymous/details", Auth.asTeacher(teacher)).status,
        )
    }

    @Test
    fun `an exercise that does not exist is refused rather than answered`() {
        assertEquals(403, api.get("/v2/unauth/exercises/99999999/anonymous/details", api.anonymous()).status)
    }

    // --- /unauth/exercises/{id}/anonymous/autoassess ----------------------------------------------

    /**
     * The one public endpoint that **runs submitted code**.
     *
     * Everything else on this surface reads a row. This takes a string from an anonymous caller and
     * hands it to a container to execute, which is the entire reason
     * `anonymous_autoassess_enabled` exists and the reason it defaults to false.
     */
    @Test
    fun `an anonymous submission is graded and the grade comes back`() {
        executor.respond(FakeExecutor.Behaviour.Grade(64, "1 of 2 tests passed"))

        val resp = api.post(
            "/v2/unauth/exercises/$publicExercise/anonymous/autoassess",
            api.body("solution" to "print(1)"),
            api.anonymous(),
        )
        assertEquals(200, resp.status) { resp.body }
        assertEquals(64, resp.jsonOrNull!!.get("grade").asInt())
        assertEquals("1 of 2 tests passed", resp.jsonOrNull!!.get("feedback").asString())

        // And what the executor was actually handed. Exactly one call: unlike the authenticated
        // path, this one has no retry, because the caller is waiting on the response.
        val sent = jacksonObjectMapper().readTree(executor.requests.single().body)
        assertEquals("print(1)", sent.get("submission").asString())

        // Drain the detached write before leaving. `insertAnonymousSubmission` launches on a bare
        // CoroutineScope and is never awaited, so without this it lands *after* the next test's
        // truncation — and the next test counts rows in a table it believes it owns.
        awaitAnonymousSubmissions(1)
    }

    @Test
    fun `an exercise not marked for anonymous use cannot be executed against`() {
        val resp = api.post(
            "/v2/unauth/exercises/$privateExercise/anonymous/autoassess",
            api.body("solution" to "import os; os.system('id')"),
            api.anonymous(),
        )
        assertEquals(403, resp.status)
        // Refused *before* anything was executed. A 403 that arrives after the container has run is
        // not an access control.
        assertTrue(executor.requests.isEmpty()) { "A refused submission was executed anyway" }
    }

    /**
     * Anonymous submissions are kept, and pruned.
     *
     * They are written on a detached coroutine after the response goes out, so nothing the caller
     * sees depends on this working — which is exactly why it can quietly stop and nobody notices
     * until someone asks what people have been submitting.
     */
    @Test
    fun `an anonymous submission is recorded`() {
        api.post(
            "/v2/unauth/exercises/$publicExercise/anonymous/autoassess",
            api.body("solution" to "print('anon')"),
            api.anonymous(),
        )

        assertEquals(1L, awaitAnonymousSubmissions(1)) { "The anonymous submission was never recorded" }
    }

    /**
     * Wait for [expected] rows in `anonymous_submission`, and return what was actually there.
     *
     * Every test that provokes one of these writes has to drain it, not only the test that asserts
     * on it. The write is launched on a detached `CoroutineScope` and never awaited, so an undrained
     * one commits at some point after its test has ended — after the next test's truncation, and
     * into a table that test believes it owns. That is a **contaminating** write rather than a
     * merely late one, and it fails in both directions: the next test's count is satisfied by the
     * stray row (a vacuous pass) or overshot by it (a failure whose message says the exact opposite
     * of what happened).
     */
    private fun awaitAnonymousSubmissions(expected: Int): Long {
        val deadline = System.currentTimeMillis() + 10_000
        var rows = 0L
        while (System.currentTimeMillis() < deadline) {
            rows = transaction { AnonymousSubmission.selectAll().count() }
            if (rows >= expected) break
            Thread.sleep(50)
        }
        return rows
    }
}
