package core.ems.service.bugreport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The YouTrack create-issue body.
 *
 * Context-free on purpose — no `@IntegrationTest`, no database, no Spring. Same reasoning as
 * `RichTextColumnsTest`: a guard that needs infrastructure is a guard that gets skipped, and until
 * this existed the body was only ever exercised by a live YouTrack, i.e. never in CI.
 *
 * What it is really protecting is the visibility clause. Everything else here would fail loudly on
 * the first filed report — a wrong project id or field name gets a 400 and a `yt_error` somebody
 * reads. A missing or widened `visibility` fails *silently and in the wrong direction*: the issue is
 * created, everything looks like it worked, and a bug report containing a student's submission is
 * readable by anyone with the URL. That asymmetry is the whole reason these assertions are worth
 * their lines.
 *
 * The shape below was verified against the real API on 2026-08-23: posted to EZ, read back with
 * `Type` as `User-submitted issue`, and confirmed to answer **404 to an unauthenticated reader**
 * while an unrestricted issue in the same project answered 200 to the same caller.
 */
class YouTrackRequestBodyTest {

    private val projectId = "0-0"
    private val groupId = "542-0"
    private val typeId = "81-29"

    private fun body(issueTypeId: String = typeId) =
        buildIssueBody(projectId, "Grades page is empty", "…the description…", groupId, issueTypeId)

    @Suppress("UNCHECKED_CAST")
    private fun visibility(body: Map<String, Any>) = body["visibility"] as Map<String, Any>

    @Suppress("UNCHECKED_CAST")
    private fun customFields(body: Map<String, Any>) = body["customFields"] as List<Map<String, Any>>

    // --- visibility, which is the one that fails silently ---------------------------------------

    @Test
    fun `every issue is restricted to the configured group`() {
        val visibility = visibility(body())

        assertEquals("LimitedVisibility", visibility["\$type"])
        assertEquals(listOf(mapOf("id" to groupId)), visibility["permittedGroups"])
    }

    @Test
    fun `visibility is present even when no type is configured`() {
        // The branch that omits customFields must not omit anything else with it. Restricting the
        // issue is not conditional on any of the optional configuration.
        assertTrue(body(issueTypeId = "").containsKey("visibility"))
    }

    // --- the type field --------------------------------------------------------------------------

    @Test
    fun `the type is sent as a single-enum custom field naming a bundle element by id`() {
        val fields = customFields(body())

        assertEquals(1, fields.size)
        val type = fields.single()
        assertEquals("SingleEnumIssueCustomField", type["\$type"])
        assertEquals("Type", type["name"])
        // By id, not by label: `User-submitted issue` is what a human reads, `81-29` is what the API
        // accepts, and only one of the two survives somebody renaming the value.
        assertEquals(mapOf("id" to typeId), type["value"])
    }

    @Test
    fun `a blank type id omits the field entirely rather than sending null`() {
        // Not the same request. A null value on a custom field means "clear it"; absent means "do not
        // mention it", which is what lets the project default apply. This branch is also the
        // documented escape hatch for a wrong id, so it has to stay a valid body.
        val body = body(issueTypeId = "")

        assertFalse(body.containsKey("customFields"))
        assertEquals(mapOf("id" to projectId), body["project"])
    }

    // --- the rest ---------------------------------------------------------------------------------

    @Test
    fun `the project is named by id, and the prose is passed through untouched`() {
        val body = body()

        assertEquals(mapOf("id" to projectId), body["project"])
        assertEquals("Grades page is empty", body["summary"])
        assertEquals("…the description…", body["description"])
    }

    @Test
    fun `nothing else is set - state, assignee and subsystem are left to triage`() {
        // Asserted rather than assumed, because the tempting next commit is to start filling these
        // in. A reporter cannot know which subsystem broke, and an auto-assigned issue is one nobody
        // triaged.
        val fields = customFields(body()).map { it["name"] }

        assertEquals(listOf("Type"), fields)
    }
}
