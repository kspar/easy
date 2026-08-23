package core.ems.service.bugreport

import core.db.Account
import core.db.BugReport
import core.db.BugReportForwardState
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

/** Where the people reading these issues are, rather than wherever the server happens to be. */
private val TALLINN: DateTimeZone = DateTimeZone.forID("Europe/Tallinn")

/**
 * Day-first with dots, which is how a date is written in Estonian. Seconds included: several reports
 * arriving within a minute is the normal shape of "I clicked it three times and nothing happened".
 */
private val HUMAN_TIME: DateTimeFormatter = DateTimeFormat.forPattern("dd.MM.yyyy HH:mm:ss")

/**
 * The timestamp as the people triaging these read it.
 *
 * Shown beside the ISO form rather than instead of it. The ISO string is what you paste into
 * `easy-core-log --since` and what pins the instant beyond argument; it is also the form nobody can
 * read at a glance, because "was 16:56:23.508Z before or after the lecture?" is a timezone
 * conversion done in your head.
 *
 * **Converted explicitly to Europe/Tallinn rather than left in the server's zone.** The instant is
 * correct either way — `created_at` is a timestamp without a zone and the same JVM writes and reads
 * it, so the offset it comes back with is the offset it went in with — but a production host running
 * UTC would render 16:56 for a report filed at 19:56, and every reader would have to know which zone
 * they were looking at. Saying `(Eesti)` in the output settles that: the country rather than the
 * `Europe/Tallinn` identifier, because the label is for the person triaging and not for a machine.
 *
 * A top-level `internal` function so the conversion is testable without a Spring context, for the
 * same reason `buildIssueBody` is: a wrong timezone or a transposed date pattern produces a
 * plausible-looking string, which is the kind of wrong that never gets noticed.
 */
internal fun humanTime(at: DateTime): String = "${at.withZone(TALLINN).toString(HUMAN_TIME)} (Eesti)"

/**
 * Turns stored bug reports into YouTrack issues, and remembers what happened.
 *
 * The state machine, [YouTrackService] is the HTTP. Split because the interesting behaviour is not
 * the request — it is what a row means once the request has failed, and how many times we are
 * willing to ask again.
 *
 * The async boundary is here, one level in from the controller, and that placement is the point.
 * `report_client_log.kt` puts `@Async` on the handler method itself, which means Spring's proxy
 * returns 200 before any work has happened and drops every exception into the executor: a validation
 * failure and a successful write are indistinguishable to the client. Here the controller commits its
 * row synchronously and reports that honestly, and only the delivery is fire-and-forget.
 */
@Service
class BugReportForwardService(
    private val youTrack: YouTrackService,
    buildPropertiesProvider: ObjectProvider<BuildProperties>,
) {
    private val log = KotlinLogging.logger {}

    // Absent under `bootRun` without `bootBuildInfo`, exactly as VersionsService documents.
    private val build: BuildProperties? = buildPropertiesProvider.ifAvailable

    fun isEnabled() = youTrack.isEnabled()

    /**
     * Files one report, or records why it could not be filed. Never throws: the caller is a
     * controller that has already answered its client, and the retry sweep is the recovery path.
     */
    @Async
    fun forward(reportId: Long) {
        if (!youTrack.isEnabled()) {
            log.debug { "YouTrack forwarding disabled, leaving bug report $reportId alone" }
            return
        }

        val report = selectReport(reportId)
        if (report == null) {
            log.warn { "Bug report $reportId vanished before it could be forwarded" }
            return
        }

        runCatching {
            youTrack.createIssue(summaryOf(report), descriptionOf(report))
        }.fold(
            onSuccess = { issueId ->
                transaction {
                    BugReport.update({ BugReport.id eq reportId }) {
                        it[ytIssueId] = issueId
                        it[ytState] = BugReportForwardState.SENT
                        it[ytAttempts] = report.attempts + 1
                        it[ytError] = null
                    }
                }
                log.info { "Bug report $reportId filed as $issueId" }
            },
            onFailure = { e ->
                // Warn, not error: the report is safe in the database and the admin mail has already
                // gone out, so this is a delayed delivery rather than lost data.
                log.warn(e) { "Could not file bug report $reportId in YouTrack, will retry" }
                transaction {
                    BugReport.update({ BugReport.id eq reportId }) {
                        it[ytState] = BugReportForwardState.FAILED
                        it[ytAttempts] = report.attempts + 1
                        it[ytError] = e.message?.take(MAX_ERROR_LENGTH)
                    }
                }
            },
        )
    }

    /**
     * Retries what the first attempt could not deliver.
     *
     * Picks up `FAILED` rows and `PENDING` ones — the latter matter more than they look: a `PENDING`
     * row with no attempts is a report that arrived and then core restarted before its `@Async` call
     * ran, which is the one failure mode that leaves no error message anywhere.
     *
     * `DISABLED` is deliberately not retried. Those reports were taken while forwarding was off; if
     * someone turns it on, they are asking for new reports to be filed, not for a year of archive to
     * arrive at once. Re-filing an old one is a manual `UPDATE`, and that is the right amount of
     * friction.
     */
    @Scheduled(cron = "\${easy.core.youtrack.retry-cron}")
    fun retryFailed() {
        if (!youTrack.isEnabled()) return

        val stale = DateTime.now().minusMinutes(RETRY_GRACE_MINUTES)
        val pending = transaction {
            BugReport.selectAll()
                .where {
                    ((BugReport.ytState eq BugReportForwardState.FAILED) or
                            (BugReport.ytState eq BugReportForwardState.PENDING)) and
                            (BugReport.ytAttempts less MAX_ATTEMPTS) and
                            (BugReport.createdAt less stale)
                }
                .orderBy(BugReport.createdAt to SortOrder.ASC)
                .limit(RETRY_BATCH)
                .map { it[BugReport.id].value }
        }

        if (pending.isEmpty()) return

        log.info { "Retrying ${pending.size} unfiled bug report(s)" }
        // Sequentially, and inside the scheduled thread rather than fanning out: a batch this small
        // against a service that just failed is not worth parallelising, and serialising it keeps a
        // YouTrack outage from costing more than one connection at a time.
        pending.forEach { forwardSync(it) }
    }

    // `forward` is @Async, so calling it from inside this bean would bypass the proxy and run
    // inline anyway. Naming that rather than relying on it.
    private fun forwardSync(reportId: Long) = forward(reportId)

    private data class StoredReport(
        val id: Long,
        val userId: String,
        val email: String,
        val createdAt: DateTime,
        val message: String,
        val diagnostics: String?,
        val pageUrl: String?,
        val webVersion: String?,
        val userAgent: String?,
        val attempts: Int,
    )

    private fun selectReport(reportId: Long): StoredReport? = transaction {
        (BugReport innerJoin Account)
            .selectAll().where { BugReport.id eq reportId }
            .map {
                StoredReport(
                    id = it[BugReport.id].value,
                    userId = it[BugReport.userId].value,
                    email = it[Account.email],
                    createdAt = it[BugReport.createdAt],
                    message = it[BugReport.message],
                    diagnostics = it[BugReport.diagnostics],
                    pageUrl = it[BugReport.pageUrl],
                    webVersion = it[BugReport.webVersion],
                    userAgent = it[BugReport.userAgent],
                    attempts = it[BugReport.ytAttempts],
                )
            }.singleOrNull()
    }

    private fun summaryOf(report: StoredReport): String {
        val firstLine = report.message.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val trimmed = if (firstLine.length > MAX_SUMMARY_LENGTH) {
            firstLine.take(MAX_SUMMARY_LENGTH - 1).trimEnd() + "…"
        } else firstLine

        return if (trimmed.isBlank()) "Bug report ${report.id}" else trimmed
    }

    /**
     * Everything a developer needs to start reproducing, assembled server-side.
     *
     * Server-side because the browser is not a trustworthy narrator of who it is. The reporter's
     * identity, the timestamp and the core version come from here; only the page URL, the web build
     * and the user agent are the client's word, and they are labelled as reported rather than
     * presented as fact.
     */
    private fun descriptionOf(report: StoredReport): String {
        val core = "${build?.version ?: "dev"} (${build?.get("commit") ?: "unknown"})"

        val diagnostics = when {
            report.diagnostics == null ->
                "_The reporter chose not to attach their recent activity._"

            report.diagnostics.isBlank() ->
                "_Recent activity was attached but empty._"

            else -> "```\n${report.diagnostics}\n```"
        }

        return """
            |${report.message}
            |
            |---
            |
            || | |
            ||---|---|
            || Reported by | `${report.userId}` (${report.email}) |
            || At | ${humanTime(report.createdAt)} · `${report.createdAt}` |
            || Page | ${report.pageUrl ?: "not reported"} |
            || Web build | ${report.webVersion ?: "not reported"} |
            || Core build | $core |
            || User agent | ${report.userAgent ?: "not reported"} |
            || Report id | ${report.id} |
            |
            |### Recent activity
            |
            |$diagnostics
            |
            |_Filed automatically from a bug report submitted in the app._
        """.trimMargin()
    }

    companion object {
        // Five, then it stays FAILED and waits for a human. A report that YouTrack has refused five
        // times is not going to succeed on the sixth; something about it is wrong, and the log line
        // plus `yt_error` is how that gets noticed.
        private const val MAX_ATTEMPTS = 5
        private const val RETRY_BATCH = 20
        private const val MAX_ERROR_LENGTH = 2000
        private const val MAX_SUMMARY_LENGTH = 100

        // Don't race the @Async attempt that is very probably still in flight for a report filed
        // seconds ago.
        private const val RETRY_GRACE_MINUTES = 5
    }
}
