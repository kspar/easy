package core.ems.service.bugreport

import core.conf.SysConf
import core.db.BugReport
import core.db.BugReportForwardState
import core.testing.Auth
import core.testing.Fixtures
import core.testing.HttpApi
import core.testing.IntegrationTest
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc

/**
 * `POST /v2/bug-reports`, end to end.
 *
 * The rate limit is the part worth testing rather than trusting, because it is the only thing
 * standing between a signed-in student and an unbounded number of admin emails and tracker issues.
 * `report_client_log.kt` is the same shape without it, and says so in its own comment.
 *
 * Note what this deliberately does **not** cover: a real YouTrack call. `easy.core.youtrack.enabled`
 * is false in the test config, so every report here takes the `DISABLED` path — which is itself
 * asserted below, since "forwarding was off" and "forwarding failed" have to stay distinguishable
 * for the retry sweep to mean anything.
 *
 * No wall clock anywhere. The hourly window is exercised by filling the cap inside one test rather
 * than by moving time, so nothing here needs `DateTime.now()` — which `NoWallClockInFixturesTest`
 * would refuse anyway.
 */
@IntegrationTest
class BugReportApiTest(@Autowired mockMvc: MockMvc) {

    private val api = HttpApi(mockMvc)

    private val student = Auth.STUDENT_ID
    private val teacher = Auth.TEACHER_ID
    private val admin = Auth.ADMIN_ID

    @BeforeEach
    fun populate() {
        transaction {
            Fixtures.student(student)
            Fixtures.teacher(teacher)
            Fixtures.admin(admin)
        }
    }

    private fun file(
        caller: org.springframework.test.web.servlet.request.RequestPostProcessor?,
        body: String,
    ) = api.post("/v2/bug-reports", body, caller)

    private fun rows() = transaction {
        BugReport.selectAll().orderBy(BugReport.id to SortOrder.ASC).map {
            BugReportRow(
                userId = it[BugReport.userId].value,
                message = it[BugReport.message],
                diagnostics = it[BugReport.diagnostics],
                pageUrl = it[BugReport.pageUrl],
                ytState = it[BugReport.ytState],
                ytIssueId = it[BugReport.ytIssueId],
                ytAttempts = it[BugReport.ytAttempts],
            )
        }
    }

    private data class BugReportRow(
        val userId: String,
        val message: String,
        val diagnostics: String?,
        val pageUrl: String?,
        val ytState: BugReportForwardState,
        val ytIssueId: String?,
        val ytAttempts: Int,
    )

    // --- 1. the report is stored ------------------------------------------------------------------

    @Test
    fun `a student files a report and it is stored against them with its diagnostics`() {
        val resp = file(
            Auth.asStudent(student),
            api.body(
                "message" to "The grade never appears",
                "diagnostics" to "09:00:00.000  api  POST /submissions 500 err=abc-123",
                "page_url" to "/courses/1/exercises/2",
                "web_version" to "v4.0 (b14b916)",
                "user_agent" to "Mozilla/5.0",
            ),
        )

        assertEquals(200, resp.status) { "Filing a report failed: ${resp.body}" }
        // The id is the receipt. A client that got 200 and no id could not quote the report later.
        assertNotNull(resp.field("id"))

        val stored = rows().single()
        assertEquals(student, stored.userId)
        assertEquals("The grade never appears", stored.message)
        assertEquals("09:00:00.000  api  POST /submissions 500 err=abc-123", stored.diagnostics)
        assertEquals("/courses/1/exercises/2", stored.pageUrl)
    }

    @Test
    fun `a teacher and an admin may file one too`() {
        assertEquals(200, file(Auth.asTeacher(teacher), api.body("message" to "Participants page is blank")).status)
        assertEquals(200, file(Auth.asAdmin(admin), api.body("message" to "Nothing works")).status)
        assertEquals(2, rows().size)
    }

    @Test
    fun `an anonymous caller is refused`() {
        assertEquals(401, file(api.anonymous(), api.body("message" to "Let me in")).status)
        assertEquals(0, rows().size)
    }

    @Test
    fun `a blank message is refused`() {
        assertEquals(400, file(Auth.asStudent(student), api.body("message" to "   ")).status)
        assertEquals(0, rows().size)
    }

    // --- 2. declining the diagnostics -------------------------------------------------------------

    @Test
    fun `an omitted diagnostics field is stored as null, not as an empty string`() {
        assertEquals(200, file(Auth.asStudent(student), api.body("message" to "It is broken")).status)

        // The distinction the column exists for: null means the reporter unticked the box, and a
        // report with no console output reads very differently once you know that is why.
        assertNull(rows().single().diagnostics)
    }

    // --- 3. forwarding is off in tests ------------------------------------------------------------

    @Test
    fun `with forwarding disabled the row records that, and is not left looking like a failure`() {
        assertEquals(200, file(Auth.asStudent(student), api.body("message" to "Something odd")).status)

        val stored = rows().single()
        // DISABLED, not PENDING and not FAILED. The retry sweep picks up the other two; if this were
        // PENDING, every report taken while the integration was off would be filed the moment
        // someone turned it on.
        assertEquals(BugReportForwardState.DISABLED, stored.ytState)
        assertNull(stored.ytIssueId)
        assertEquals(0, stored.ytAttempts)
    }

    // --- 4. the rate limit ------------------------------------------------------------------------

    @Test
    fun `the hourly cap refuses further reports from the same caller`() {
        SysConf.putProp(CommonCreateBugReportController.MAX_PER_HOUR_PROP, "2")

        assertEquals(200, file(Auth.asStudent(student), api.body("message" to "First")).status)
        assertEquals(200, file(Auth.asStudent(student), api.body("message" to "Second")).status)

        val refused = file(Auth.asStudent(student), api.body("message" to "Third"))
        assertEquals(400, refused.status)
        assertEquals("BUG_REPORT_RATE_LIMITED", refused.errorCode)

        // Refused means not stored. A limit that counted the rejected attempt would lock the caller
        // out for an hour on their first retry.
        assertEquals(2, rows().size)
    }

    @Test
    fun `the cap is per caller, so one student cannot lock out another`() {
        SysConf.putProp(CommonCreateBugReportController.MAX_PER_HOUR_PROP, "1")

        assertEquals(200, file(Auth.asStudent(student), api.body("message" to "Mine")).status)
        assertEquals(400, file(Auth.asStudent(student), api.body("message" to "Mine again")).status)

        // Same window, different person, still allowed.
        assertEquals(200, file(Auth.asTeacher(teacher), api.body("message" to "Theirs")).status)
        assertEquals(2, rows().size)
    }

    @Test
    fun `an absent system property falls back to the default rather than to no limit`() {
        // Nothing written to system_configuration here on purpose: the tables are truncated between
        // tests, so this is the fresh-database case. A missing row must mean ten, not unlimited —
        // getProp returning null and the code reading that as "no cap" is exactly the bug this
        // catches.
        assertNull(SysConf.getProp(CommonCreateBugReportController.MAX_PER_HOUR_PROP))

        repeat(CommonCreateBugReportController.DEFAULT_MAX_PER_HOUR) { i ->
            assertEquals(200, file(Auth.asStudent(student), api.body("message" to "Report $i")).status)
        }

        val refused = file(Auth.asStudent(student), api.body("message" to "One too many"))
        assertEquals("BUG_REPORT_RATE_LIMITED", refused.errorCode)
    }
}
