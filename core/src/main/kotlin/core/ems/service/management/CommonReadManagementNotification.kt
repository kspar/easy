package core.ems.service.management

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.ManagementNotification
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


/**
 * The severities, most important first. An unknown value sorts last rather than throwing: a row
 * written by a future version should render plainly, not take the banner down for everyone.
 */
private val SEVERITY_ORDER = listOf("URGENT", "INFO")

@RestController
@RequestMapping("/v2")
class CommonReadManagementNotificationsController {
    private val log = KotlinLogging.logger {}

    data class Resp(
        @get:JsonProperty("messages")
        @get:JsonInclude(JsonInclude.Include.NON_NULL) val messages: List<MessageResp>
    )

    data class MessageResp(
        // The id is what the client keys dismissal on, so it has to be stable and it has to be here.
        @get:JsonProperty("id") val id: String,
        @get:JsonProperty("message") val message: String,
        @get:JsonProperty("severity") val severity: String,
        @get:JsonInclude(JsonInclude.Include.NON_NULL)
        @get:JsonProperty("link_url") val linkUrl: String?,
        @get:JsonInclude(JsonInclude.Include.NON_NULL)
        @get:JsonProperty("link_label") val linkLabel: String?,
    )

    /**
     * The messages this caller should be seeing right now — and nothing else.
     *
     * Polled by the web client rather than pushed. Latency here is minutes, not milliseconds:
     * "maintenance in two hours" does not need a socket, and one long-lived connection per user
     * through the proxy is a real operational change for no benefit at this scale.
     */
    @Secured("ROLE_ADMIN", "ROLE_TEACHER", "ROLE_STUDENT")
    @GetMapping("/management/common/notifications")
    fun controller(caller: EasyUser): Resp {
        log.debug { "Getting visible system notifications for ${caller.id}" }
        return selectVisibleFor(caller)
    }

    /**
     * Filtering happens here, not in the client.
     *
     * A maintenance notice scheduled for next week, sitting unrendered in a JSON response, has been
     * announced — whichever component decided not to draw it. The same goes for a message aimed at
     * teachers arriving in a student's browser. So the window and the audience are both applied in
     * SQL, and the response contains only what this person may see at this moment.
     */
    private fun selectVisibleFor(caller: EasyUser): Resp = transaction {
        val now = DateTime.now()

        Resp(
            ManagementNotification.selectAll()
                // The whole predicate is built inside this lambda on purpose: `eq`, `lessEq` and
                // friends are members of SqlExpressionBuilder, which is the receiver here and
                // nowhere else. Assembling any part of it outside does not fail at the query — it
                // fails to compile, with an unresolved reference that reads like a missing import.
                .where {
                    // Null is "no bound" on both sides, which is what makes rows written before
                    // scheduling existed still visible rather than silently filtered out.
                    val started = ManagementNotification.visibleFrom.isNull() or
                            (ManagementNotification.visibleFrom lessEq now)
                    val notEnded = ManagementNotification.visibleUntil.isNull() or
                            (ManagementNotification.visibleUntil greater now)

                    // Role targeting follows the caller's *effective* roles rather than the one they
                    // are currently acting as: somebody who is both a teacher and an admin should
                    // see messages aimed at either, since which hat they are wearing in the UI says
                    // nothing about which announcements concern them.
                    val audience = listOfNotNull(
                        ManagementNotification.forStudents.takeIf { caller.isStudent() },
                        ManagementNotification.forTeachers.takeIf { caller.isTeacher() },
                        ManagementNotification.forAdmins.takeIf { caller.isAdmin() },
                    ).map { it eq true }.reduceOrNull { a, b -> a or b }

                    // A caller with no recognised role matches no audience, so they see nothing.
                    // Cannot happen today — the endpoint is @Secured to the three roles — but
                    // "shows everything" would be the wrong way to be wrong if that ever changes.
                    if (audience == null) Op.FALSE else started and notEnded and audience
                }
                // Newest first within a severity; the severities are ordered below.
                .orderBy(ManagementNotification.id to SortOrder.DESC)
                .map {
                    MessageResp(
                        it[ManagementNotification.id].value.toString(),
                        it[ManagementNotification.message],
                        it[ManagementNotification.severity],
                        it[ManagementNotification.linkUrl],
                        it[ManagementNotification.linkLabel],
                    )
                }
                // Urgent first — a maintenance notice must not sit underneath a feature tip.
                //
                // Ranked explicitly rather than sorted by the stored string. `ORDER BY severity`
                // happens to put INFO before URGENT, which is the wrong way round and is wrong for
                // a reason that has nothing to do with importance; a third severity would reshuffle
                // it again by spelling. `sortedBy` is stable, so the id ordering above survives.
                .sortedBy { SEVERITY_ORDER.indexOf(it.severity).takeIf { i -> i >= 0 } ?: SEVERITY_ORDER.size })
    }
}
