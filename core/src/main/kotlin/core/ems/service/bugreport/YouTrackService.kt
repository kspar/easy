package core.ems.service.bugreport

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.restclient.RestTemplateBuilder
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import java.time.Duration

/** The project field a filed issue's type goes in. Stable, and readable in the project schema. */
internal const val TYPE_FIELD_NAME = "Type"

/**
 * The create-issue request body.
 *
 * A top-level function rather than a method on the service, so that it can be tested without a
 * Spring context and without a live YouTrack — which, until it was pulled out here, was the only
 * thing that ever exercised it. See `YouTrackRequestBodyTest`.
 *
 * Untyped maps on purpose. `$type` is a discriminator YouTrack requires, and Kotlin classes carrying
 * a property called `$type` cost more in annotations than the four nested maps they would replace.
 *
 * ### Verified against the real API on 2026-08-23
 *
 * This exact shape was posted to EZ and read back: `Type` came out as `User-submitted issue`, and the
 * issue returned **404 to an unauthenticated reader** while an unrestricted issue in the same project
 * returned 200 to the same caller. That control is the half that matters — a 404 on its own would
 * equally well mean a malformed request or an instance with guest access turned off.
 */
internal fun buildIssueBody(
    projectId: String,
    summary: String,
    description: String,
    visibilityGroupId: String,
    issueTypeId: String,
): Map<String, Any> {
    val body = mutableMapOf<String, Any>(
        "project" to mapOf("id" to projectId),
        "summary" to summary,
        "description" to description,

        // Unconditional, and not a parameter. The text box a report comes from is unbounded, and
        // this instance has guest access — there is no caller-supplied condition under which
        // publishing one of these is correct, so there is nothing to pass in. `permittedGroups`
        // takes a group id; EZ Team happens to be a ProjectTeam rather than a plain UserGroup, and
        // YouTrack accepts it here either way.
        "visibility" to mapOf(
            "\$type" to "LimitedVisibility",
            "permittedGroups" to listOf(mapOf("id" to visibilityGroupId)),
        ),
    )

    // Omitted entirely when unset, rather than sent as null — a null value on a custom field means
    // "clear it", which is a different request from "do not mention it".
    if (issueTypeId.isNotBlank()) {
        body["customFields"] = listOf(
            mapOf(
                // Type is a single-value enum field, so this is the payload type and the value is a
                // bundle element referenced by id.
                "\$type" to "SingleEnumIssueCustomField",
                "name" to TYPE_FIELD_NAME,
                "value" to mapOf("id" to issueTypeId),
            ),
        )
    }

    return body
}

/**
 * Creates YouTrack issues from bug reports.
 *
 * The HTTP boundary only — no database, no state machine. [BugReportForwardService] owns what a
 * failure here means for the row that caused it.
 *
 * Two things about YouTrack's REST API are worth knowing before editing this:
 *
 * **It wants internal ids, not the names people use.** `EZ` is a *short name*; the API needs the
 * project's opaque id, and the same goes for the group in `visibility` and the `Type` value. Keeping
 * them as configuration means no lookup call, no admin-read scope on this token, and no startup
 * dependency on the tracker being reachable. `doc/bug-reporting.md` §5.1 records how to find them.
 *
 * A trap worth recording, because it cost a wrong conclusion: **`EZ Team` is a `ProjectTeam`, not a
 * plain `UserGroup`**, so `GET /api/admin/groups/{id}` answers 404 for it and it looks like the id is
 * wrong. `GET /api/groups` lists it, and `GET /api/admin/projects/{id}?fields=team(id,name)` names it
 * directly. It works perfectly well in `permittedGroups`.
 *
 * **One custom field is set: `Type`.** An earlier version of this set none, on the grounds that a
 * bundle lookup was a lot of moving parts for a field triage sets in one click. EZ then gained a
 * dedicated `User-submitted issue` type, and being handed the element id removes the lookup that was
 * the whole objection — so reports from the wild are now a filterable class rather than
 * indistinguishable from a triaged bug. Nothing else is set: `State`, `Assignee` and `Subsystem` are
 * triage's to decide, and a reporter cannot know which subsystem broke.
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
     * The `Type` value to stamp on a filed issue — a bundle element id, not the label.
     *
     * Blank means "leave the field alone and let the project default apply", which is both the
     * out-of-the-box behaviour and the escape hatch: if this id is ever wrong, blanking it gets
     * issue filing working again without a code change.
     */
    @Value("\${easy.core.youtrack.issue-type-id}")
    private lateinit var issueTypeId: String

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
        val body = buildIssueBody(projectId, summary, description, visibilityGroupId, issueTypeId)

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
