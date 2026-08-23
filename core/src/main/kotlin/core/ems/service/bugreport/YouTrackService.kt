package core.ems.service.bugreport

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.restclient.RestTemplateBuilder
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * Creates YouTrack issues from bug reports.
 *
 * The HTTP boundary only — no database, no state machine. [BugReportForwardService] owns what a
 * failure here means for the row that caused it.
 *
 * Two things about YouTrack's REST API are worth knowing before editing this:
 *
 * **It wants internal ids, not the names people use.** `EZ` is a *short name*; the API needs the
 * project's opaque id, and the same goes for the group in `visibility`. Resolving either at runtime
 * means `GET /api/admin/…`, which is low-level admin read scope this token has no reason to hold, so
 * both ids are configuration. `doc/bug-reporting.md` records the two `curl`s that find them.
 *
 * **Custom fields are deliberately not set.** Filing these as `Type: Bug` needs the per-field
 * `$type` discriminator and a bundle lookup, which is a lot of moving parts for a field triage sets
 * in one click. The project default applies instead.
 */
@Service
class YouTrackService {
    private val log = KotlinLogging.logger {}

    @Value("\${easy.core.youtrack.enabled}")
    private var enabled: Boolean = false

    @Value("\${easy.core.youtrack.base-url}")
    private lateinit var baseUrl: String

    @Value("\${easy.core.youtrack.token}")
    private lateinit var token: String

    @Value("\${easy.core.youtrack.project-id}")
    private lateinit var projectId: String

    @Value("\${easy.core.youtrack.visibility-group-id}")
    private lateinit var visibilityGroupId: String

    /**
     * Whether a report should even be queued for forwarding.
     *
     * Enabled *and* configured, checked together on purpose. A half-configured integration must not
     * turn every bug report into a 500 — the report is the thing worth keeping, and a missing id is
     * an operator's problem to see in the log, not the reporter's problem to hit in the UI.
     */
    fun isEnabled(): Boolean {
        if (!enabled) return false

        val missing = listOfNotNull(
            "base-url".takeIf { baseUrl.isBlank() },
            "token".takeIf { token.isBlank() || token.startsWith(TOKEN_PLACEHOLDER_PREFIX) },
            "project-id".takeIf { projectId.isBlank() },
            "visibility-group-id".takeIf { visibilityGroupId.isBlank() },
        )

        if (missing.isNotEmpty()) {
            log.warn {
                "YouTrack forwarding is enabled but not configured (missing: ${missing.joinToString()}). " +
                        "Bug reports will be stored and emailed, not filed."
            }
            return false
        }

        return true
    }

    /**
     * Files one issue and returns its readable id, e.g. `EZ-1786`. Throws if YouTrack says no.
     *
     * Visibility is restricted here, unconditionally, and it is not a parameter. A bug report's text
     * box is unbounded: a student pastes their submission, a teacher quotes feedback they wrote about
     * a named person. The instance has guest access. There is no caller-supplied condition under
     * which making one of these public is correct, so there is nothing to pass in.
     */
    fun createIssue(summary: String, description: String): String {
        // Untyped bodies on purpose. `$type` is a Jackson discriminator YouTrack requires on the
        // visibility object, and expressing that as annotated Kotlin classes costs more than the
        // three nested maps it would replace.
        val body = mapOf(
            "project" to mapOf("id" to projectId),
            "summary" to summary,
            "description" to description,
            "visibility" to mapOf(
                "\$type" to "LimitedVisibility",
                "permittedGroups" to listOf(mapOf("id" to visibilityGroupId)),
            ),
        )

        val headers = HttpHeaders().apply {
            setBearerAuth(token)
            contentType = MediaType.APPLICATION_JSON
        }

        // Seconds, not minutes. Nothing waits on this — it runs after the response has gone out —
        // but a hung connection holds a pool thread, and the retry sweep exists precisely so that
        // giving up quickly costs nothing.
        val client = RestTemplateBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .readTimeout(REQUEST_TIMEOUT)
            .build()

        val response = client.postForObject(
            "$baseUrl$CREATE_ISSUE_PATH", HttpEntity(body, headers), Map::class.java
        )

        val issueId = response?.get("idReadable") as? String
            ?: throw IllegalStateException("YouTrack accepted the issue but returned no idReadable: $response")

        log.info { "Filed YouTrack issue $issueId" }
        return issueId
    }

    companion object {
        private val REQUEST_TIMEOUT = Duration.ofSeconds(15)

        // `fields` is not optional: without it YouTrack returns only the internal id, and the
        // readable one is what a human needs to find the issue.
        private const val CREATE_ISSUE_PATH = "/api/issues?fields=id,idReadable"

        // What ansible writes into secrets.yaml when it creates the key for the first time. Treated
        // as absent rather than as a token, so a freshly provisioned host degrades quietly instead
        // of authenticating as nobody once per bug report.
        private const val TOKEN_PLACEHOLDER_PREFIX = "CHANGEME"
    }
}
