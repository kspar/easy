package core.conf.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Turning claim text into roles, and specifically that there is now one answer rather than two.
 *
 * Roles reach core in two shapes: a JSON array in the `easy_role` JWT claim, and a comma-separated
 * string in the `oidc_claim_easy_role` header the auth-disabled dev path uses. There were two parses
 * to match, and they disagreed — the header split on comma, the JWT single-string fallback wrapped the
 * whole value as one role. So a realm whose mapper emitted `easy_role: "student,teacher"` as one
 * string got 401 "unmapped role" in production, while the identical string in a dev header
 * authenticated as two roles. The fallback existed, by its own comment, "rather than lock out a user
 * over realm config", and for the multi-role case it did exactly that.
 *
 * Neither trimmed, which was the sharper half: `student, teacher` in a header threw an uncaught
 * `RuntimeException` out of `DummyZeroAuthFilter` — a 500, where the JWT path would at least have said
 * 401.
 *
 * [bothPathsAgree] is the test that matters here. The individual cases below could all pass while the
 * two paths still diverged somewhere else; that one compares them directly.
 */
class RoleParsingTest {

    private fun names(roles: Set<EasyGrantedAuthority>) = roles.map { it.authority }.sorted()

    // --- what the normalisation accepts ----------------------------------------------------------

    @Test
    fun `a comma-separated string is several roles, on both paths`() {
        assertEquals(listOf("ROLE_STUDENT", "ROLE_TEACHER"), names(mapHeaderToRoles("student,teacher")))
        assertEquals(
            listOf("ROLE_STUDENT", "ROLE_TEACHER"),
            names(mapRoleStringsToRoles(normaliseRoleStrings(listOf("student,teacher")))),
        )
    }

    @Test
    fun `whitespace around a separator is not part of the role`() {
        // This was the 500: `student, teacher` split to " teacher", which matched nothing and threw
        // out of a filter that does not catch.
        assertEquals(listOf("ROLE_STUDENT", "ROLE_TEACHER"), names(mapHeaderToRoles("student, teacher")))
        assertEquals(listOf("ROLE_ADMIN"), names(mapHeaderToRoles("  admin  ")))
    }

    @Test
    fun `a stray separator does not become a role`() {
        // Dropping an empty fragment is not the same as dropping a role — see the note on
        // normaliseRoleStrings. A trailing comma is a formatting artefact of the separator, not a
        // claim about what the user may do.
        assertEquals(listOf("ROLE_STUDENT"), names(mapHeaderToRoles("student,")))
        assertEquals(listOf("ROLE_STUDENT"), names(mapHeaderToRoles(",student")))
        assertEquals(listOf("ROLE_STUDENT"), names(mapHeaderToRoles("student,,")))
    }

    @Test
    fun `array elements are normalised too, not only strings that had to be split`() {
        // The array path is the production one and had no normalisation at all, so a realm emitting
        // `["student", " teacher"]` would have failed on the second element.
        assertEquals(
            listOf("ROLE_STUDENT", "ROLE_TEACHER"),
            names(mapRoleStringsToRoles(normaliseRoleStrings(listOf("student", " teacher")))),
        )
    }

    @Test
    fun `nothing usable normalises to nothing, rather than to a role named empty string`() {
        // Which is what lets EasyUserJwtConverter report "empty easy_role" instead of
        // "unmapped role ''" — a claim that carries nothing is a realm misconfiguration, and the
        // message should say that rather than blaming a role name nobody wrote.
        assertEquals(emptyList<String>(), normaliseRoleStrings(listOf("")))
        assertEquals(emptyList<String>(), normaliseRoleStrings(listOf("  ", ",", " , ")))
        assertEquals(emptyList<String>(), normaliseRoleStrings(emptyList()))
    }

    // --- what it still refuses -------------------------------------------------------------------

    @Test
    fun `an unrecognised role still throws rather than being dropped`() {
        // The KDoc on mapRoleStringsToRoles is the reason: silently ignoring a role we cannot map
        // would quietly change what a user can do. Normalisation must not soften that.
        assertThrows(RuntimeException::class.java) { mapHeaderToRoles("student,wizard") }
        assertThrows(RuntimeException::class.java) { mapRoleStringsToRoles(listOf("wizard")) }
    }

    // --- the actual finding ----------------------------------------------------------------------

    @Test
    fun bothPathsAgree() {
        // One value, both parses, same answer. This is the guard against the two copies drifting
        // again: any future change that touches one path and not the other fails here rather than
        // in production, where the symptom was a 401 that meant "your realm joined the roles with a
        // comma".
        val values = listOf(
            "student",
            "teacher",
            "admin",
            "student,teacher",
            "student, teacher",
            "admin,student,teacher",
            "  teacher  ",
            "student,",
        )
        values.forEach { value ->
            assertEquals(
                names(mapHeaderToRoles(value)),
                names(mapRoleStringsToRoles(normaliseRoleStrings(listOf(value)))),
                "the two role-parsing paths disagree about '$value'",
            )
        }
    }
}
