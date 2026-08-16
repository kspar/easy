package core.aas

import core.conf.SysConf
import core.db.AutoGradeStatus
import core.db.AutogradeActivity
import core.db.ExecutorContainerImage
import core.db.StatsSubmission
import core.db.Submission
import core.db.Executor as ExecutorTable
import core.aas.EXECUTOR_REQUEST_TIMEOUT_SECONDS_KEY
import core.testing.Auth
import core.testing.FakeExecutor
import core.testing.Fixtures
import core.testing.HttpApi
import core.testing.IntegrationTest
import core.testing.TestClock
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * Submission → grading → feedback, in CI.
 *
 * `doc/testing.md` calls this the application's central promise, and then puts it on a deployed
 * environment because exercising it looks like it needs Docker. It does not: the only thing beyond
 * this process is a container running Python, and everything that can plausibly break is on this
 * side of the socket — a coroutine launched from a controller, a scheduler with per-priority queues,
 * a `RestTemplate` call, Jackson both ways, three tables and a four-state status machine.
 *
 * So the executor is [FakeExecutor], 40 lines of `com.sun.net.httpserver` from the JDK, and the path
 * under test is production's all the way down to the wire.
 *
 * ### The failure legs are the point
 *
 * A grading path that works when everything works is not the interesting half. What a student sees
 * when an executor returns 500, or hangs, or when core restarts mid-grade, is decided by three
 * separate mechanisms — the retry in `autoAssessAsync`, `insertAutoAssFailed`, and
 * `statusInProgressToFailed` at startup — none of which had a test, and all of which fail *quietly*:
 * the submission simply sits at IN_PROGRESS forever, which the UI renders as a spinner.
 *
 * ### Waiting
 *
 * Submitting returns before grading finishes: the controller launches a coroutine and hands the
 * observer the job. So every assertion here polls, and the poll is on the **database**, which is
 * what the student's next page load reads. `AutoGradeScheduler.grade()` runs on a 3-second timer in
 * the test config, so nothing here is faster than that; the timeouts are generous accordingly.
 */
@IntegrationTest
class AutoGradeIntegrationTest(
    @Autowired mockMvc: MockMvc,
    @Autowired private val scheduler: AutoGradeScheduler,
    @Autowired private val applicationContext: ApplicationContext,
) {

    private val api = HttpApi(mockMvc)

    private val teacher = Auth.TEACHER_ID
    private val student = Auth.STUDENT_ID

    private lateinit var executor: FakeExecutor
    private var courseId = 0L
    private var ceId = 0L

    @BeforeEach
    fun populate() {
        TestClock.reset()
        executor = FakeExecutor()

        transaction {
            Fixtures.teacher(teacher)
            Fixtures.student(student)
            courseId = Fixtures.course("Autograding")
            Fixtures.enrolTeacher(courseId, teacher)
            Fixtures.enrolStudent(courseId, student)
            val exerciseId = Fixtures.autoExercise("Sum two numbers", teacher, gradingScript = "check_sum()")
            Fixtures.asset(exerciseId, "helper.py", "def helper(): pass")
            ceId = Fixtures.courseExercise(courseId, exerciseId, threshold = 80)
            Fixtures.executor("test", executor.baseUrl)
        }

        // The scheduler holds its queues in memory and refreshes from the database on a 60-second
        // timer, so a row inserted after the context started is invisible until it is told. Without
        // this every test here would fail as "no capable executors", which looks like a fixture
        // problem and is really a lifecycle one.
        scheduler.syncExecutorsFromDB()
    }

    @AfterEach
    fun stopExecutor() {
        executor.close()
        // The row is truncated before the next test, but the scheduler's map is not. Left alone it
        // would keep a queue pointing at a closed server, and `grade()` asks the database for each
        // known executor's max load every cycle — which is the NoSuchElementException-every-3-seconds
        // that `syncExecutorsFromDB` was extended to prevent.
        transaction {
            // The join rows first: executor_container_image references executor, and truncation
            // between tests happens *before* the next one rather than after this one.
            ExecutorContainerImage.deleteAll()
            ExecutorTable.deleteAll()
        }
        scheduler.syncExecutorsFromDB()
    }

    // --- the promise ------------------------------------------------------------------------------

    @Test
    fun `a submission is graded, and the grade and feedback reach the student`() {
        executor.respond(FakeExecutor.Behaviour.Grade(93, "3 of 3 tests passed"))

        val submissionId = submit("print(1 + 1)")
        awaitStatus(submissionId, AutoGradeStatus.COMPLETED)

        val row = transaction {
            Submission.selectAll().where { Submission.id eq submissionId }.single()
        }
        assertEquals(93, row[Submission.grade])
        assertEquals(true, row[Submission.isAutoGrade])
        assertEquals(true, row[Submission.isGradedDirectly])

        val activity = transaction {
            AutogradeActivity.selectAll().where { AutogradeActivity.submission eq submissionId }.single()
        }
        assertEquals(93, activity[AutogradeActivity.grade])
        assertEquals("3 of 3 tests passed", activity[AutogradeActivity.feedback])

        // And the student can read it back through the API, which is the only assertion here that
        // reflects what a person actually sees.
        val details = api.get("/v2/student/courses/$courseId/exercises/$ceId", Auth.asStudent(student))
        assertEquals(200, details.status) { details.body }
    }

    /**
     * What core sends the executor.
     *
     * A status assertion cannot see this: a grading request carrying the wrong solution, or an
     * exercise's assets missing, still produces a perfectly good-looking grade — because the fake
     * grades whatever it is given, and so, in the sense that matters, does a real executor. It would
     * be the *student's* result that was wrong, on a run nobody could reproduce.
     */
    @Test
    fun `the request carries the solution, the grading script, the assets and the limits`() {
        submit("print('my answer')").also { awaitStatus(it, AutoGradeStatus.COMPLETED) }

        val sent = json(executor.requests.single().body)
        assertEquals("print('my answer')", sent.get("submission").asString())
        assertEquals("check_sum()", sent.get("grading_script").asString())
        assertEquals("test-image", sent.get("image_name").asString())
        assertEquals(6, sent.get("max_time_sec").asInt())
        assertEquals(300, sent.get("max_mem_mb").asInt())

        val assets = sent.get("assets").toList()
        assertEquals(1, assets.size) { "The exercise's assets did not reach the executor: $assets" }
        assertEquals("helper.py", assets[0].get("file_name").asString())
        assertEquals("def helper(): pass", assets[0].get("file_content").asString())
    }

    @Test
    fun `a zero grade is recorded as a grade, not as an absent one`() {
        // 0 is falsy in the language this eventually reaches, and it is also the most common real
        // result — a submission that compiles and fails everything. A `!grade` anywhere between here
        // and the screen turns it into "not graded yet".
        executor.respond(FakeExecutor.Behaviour.Grade(0, "0 of 3 tests passed"))

        val submissionId = submit("nonsense")
        awaitStatus(submissionId, AutoGradeStatus.COMPLETED)

        assertEquals(0, transaction { Submission.selectAll().where { Submission.id eq submissionId }.single() }
            .let { it[Submission.grade] })
    }

    @Test
    fun `the statistics row is filled in as well as the submission`() {
        // `stats_submission` is what the admin dashboards read. It is written at submit time with no
        // grade and updated when the grade arrives, so a broken update leaves a permanent zero in
        // the reporting and nothing anywhere else looks wrong.
        val submissionId = submit("print(1)")
        awaitStatus(submissionId, AutoGradeStatus.COMPLETED)

        val stats = transaction {
            StatsSubmission.selectAll().where { StatsSubmission.submissionId eq submissionId }.single()
        }
        assertEquals(100, stats[StatsSubmission.autoPoints])
        assertNotNull(stats[StatsSubmission.autoGradedAt])
    }

    // --- the failure legs -------------------------------------------------------------------------

    /**
     * An executor that answers 500 is retried exactly once, then the submission is marked FAILED.
     *
     * The retry is EZ-1214's, and it is worth pinning in both directions. Two calls rather than one
     * is the retry working; two rather than *many* is the absence of a loop that would hammer a
     * failing executor for every submission in the queue.
     */
    @Test
    fun `an executor error is retried once and then recorded as failed`() {
        executor.respond(FakeExecutor.Behaviour.Fail(500))

        val submissionId = submit("print(1)")
        awaitStatus(submissionId, AutoGradeStatus.FAILED)

        assertEquals(2, executor.requests.size) { "Expected one call and one retry, got ${executor.requests.size}" }
        assertNull(transaction { Submission.selectAll().where { Submission.id eq submissionId }.single() }
            .let { it[Submission.grade] })
        assertTrue(transaction {
            AutogradeActivity.selectAll().where { AutogradeActivity.submission eq submissionId }.empty()
        }) { "A failed grading wrote an autograde activity" }
    }

    @Test
    fun `a response core cannot parse is a failure, not a grade of zero`() {
        // The dangerous shape: a body that deserialises into an ExecutorResponse with defaults would
        // silently award 0. Jackson refusing it is what keeps that from happening, and this is the
        // assertion that says so.
        executor.respond(FakeExecutor.Behaviour.Garbage("""{"not":"a grade"}"""))

        val submissionId = submit("print(1)")
        awaitStatus(submissionId, AutoGradeStatus.FAILED)
        assertNull(transaction { Submission.selectAll().where { Submission.id eq submissionId }.single() }
            .let { it[Submission.grade] })
    }

    /**
     * A submission left IN_PROGRESS by a restart is moved to FAILED at startup.
     *
     * Without it those rows stay IN_PROGRESS forever — the UI shows a spinner that never resolves,
     * and `AutoAssessStatusObserver` has no job to hand anyone, because the process that owned it is
     * gone. This is the one recovery path in the whole autograding flow, it runs exactly once per
     * boot, and nothing exercised it.
     *
     * The private method is reached through the `ContextRefreshedEvent` listener, which is how
     * production reaches it too.
     */
    @Test
    fun `a submission stranded in progress by a restart is failed at startup`() {
        val submissionId = submit("print(1)")
        awaitStatus(submissionId, AutoGradeStatus.COMPLETED)

        // Put it back into the state a killed JVM leaves behind.
        transaction {
            Submission.update({ Submission.id eq submissionId }) { it[autoGradeStatus] = AutoGradeStatus.IN_PROGRESS }
        }

        scheduler.onApplicationEvent(ContextRefreshedEvent(applicationContext))

        assertEquals(AutoGradeStatus.FAILED, statusOf(submissionId))
    }

    /**
     * An executor that accepts the request and never answers.
     *
     * The realistic version of "the grader is wedged": the socket is open, the request was read, and
     * nothing comes back. What stops core waiting forever is `executor-request-timeout-seconds` in
     * `system_configuration` — **a database row, not a config file**, so it is one `DELETE` away from
     * the fallback of 3600 seconds. An hour is a submission that appears stuck for the rest of the
     * lesson, and the only symptom is a spinner.
     *
     * Set to one second here, which is also the assertion that the row is read at all.
     */
    @Test
    fun `an executor that never answers times out and fails`() {
        SysConf.putProp(EXECUTOR_REQUEST_TIMEOUT_SECONDS_KEY, "1")
        executor.respond(FakeExecutor.Behaviour.Hang)

        val submissionId = submit("print(1)")
        awaitStatus(submissionId, AutoGradeStatus.FAILED)

        // Once for the attempt and once for the retry, both timed out. A single request would mean
        // the retry had been skipped; more would mean something was looping.
        assertEquals(2, executor.requests.size)
    }

    @Test
    fun `an exercise with no capable executor fails rather than hanging`() {
        // A drained executor is the realistic version: it is up, it is registered, and it must not
        // be given new work. A submission arriving while every executor is drained has to end
        // somewhere — FAILED, visibly — rather than sitting IN_PROGRESS until someone notices.
        transaction { ExecutorTable.update { it[drain] = true } }
        scheduler.syncExecutorsFromDB()

        val submissionId = submit("print(1)")
        awaitStatus(submissionId, AutoGradeStatus.FAILED)
        assertEquals(0, executor.requests.size) { "A drained executor was given work" }
    }

    // --- helpers ----------------------------------------------------------------------------------

    private fun submit(solution: String): Long {
        val resp = api.post(
            "/v2/student/courses/$courseId/exercises/$ceId/submissions",
            api.body("solution" to solution),
            Auth.asStudent(student),
        )
        assertEquals(200, resp.status) { resp.body }

        return transaction {
            Submission.select(Submission.id)
                .where { Submission.courseExercise eq ceId }
                .map { it[Submission.id].value }
                .max()
        }
    }

    private fun statusOf(submissionId: Long): AutoGradeStatus = transaction {
        Submission.select(Submission.autoGradeStatus)
            .where { Submission.id eq submissionId }
            .map { it[Submission.autoGradeStatus] }
            .single()
    }

    /**
     * Poll the database until the status settles, or fail saying what it settled on instead.
     *
     * A bare `Thread.sleep` long enough for the 3-second scheduler tick would make every test here
     * take three seconds whether or not it needed to, and would still be a guess. Polling is neither.
     */
    private fun awaitStatus(submissionId: Long, expected: AutoGradeStatus, timeoutMs: Long = 30_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = statusOf(submissionId)
        while (System.currentTimeMillis() < deadline) {
            last = statusOf(submissionId)
            if (last == expected) return
            Thread.sleep(50)
        }
        assertEquals(expected, last) { "Submission $submissionId never reached $expected within ${timeoutMs}ms" }
    }

    private fun json(body: String): JsonNode = jacksonObjectMapper().readTree(body)
}
