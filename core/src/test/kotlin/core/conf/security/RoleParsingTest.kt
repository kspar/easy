package core.conf.security

import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException

/**
 * Turning claim text into roles, and specifically that the two paths that do it agree.
 *
 * Roles reach core in two shapes: a JSON array in the `easy_role` JWT claim, and a comma-separated
 * string in the `oidc_claim_easy_role` header the auth-disabled dev path uses. Each shape had its own
 * parse and they disagreed — the header split on comma, the JWT single-string fallback wrapped the
 * whole value as one role, so a realm emitting `easy_role: "student,teacher"` as one string was
 * rejected in production and accepted in dev. Neither trimmed.
 *
 * **Both sides here drive real code**, and that is the point of this file rather than an incidental
 * detail. The first version of [bothPathsAgree] compared `mapHeaderToRoles(v)` against
 * `mapRoleStringsToRoles(normaliseRoleStrings(listOf(v)))` — and `mapHeaderToRoles` was *defined* as
 * exactly that expression, so the assertion compared a value to itself and could not fail for any
 * input. It was billed as the drift guard while guarding nothing: deleting the converter's
 * normalisation would have left it green. So the header side now runs [DummyZeroAuthFilter] and the
 * JWT side runs [EasyUserJwtConverter], which are the two things that can actually drift.
 */
class RoleParsingTest {

    private val converter = EasyUserJwtConverter()

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    // --- the two real paths ----------------------------------------------------------------------

    /** Roles the JWT path grants for an `easy_role` claim, or null if it refuses the token. */
    private fun viaJwt(claim: Any): List<String>? {
        val jwt = Jwt.withTokenValue("irrelevant")
            .header("alg", "RS256")
            .claim("preferred_username", "dev")
            .claim("email", "dev@test.ee")
            .claim("easy_role", claim)
            .build()
        return try {
            authorityNames((converter.convert(jwt) as EasyUser).roles)
        } catch (e: InvalidBearerTokenException) {
            null
        }
    }

    /**
     * Roles the dev header path grants for an `oidc_claim_easy_role` value, or null if it declines to
     * authenticate — which for a filter means leaving the context unset, so the chain answers 401.
     */
    private fun viaHeader(header: String): List<String>? {
        val request = MockHttpServletRequest("GET", "/v2/versions").apply {
            addHeader("oidc_claim_preferred_username", "dev")
            addHeader("oidc_claim_email", "dev@test.ee")
            addHeader("oidc_claim_easy_role", header)
        }
        val response: HttpServletResponse = MockHttpServletResponse()
        SecurityContextHolder.clearContext()
        DummyZeroAuthFilter().doFilter(request, response, MockFilterChain())
        val authentication = SecurityContextHolder.getContext().authentication
        return (authentication as EasyUser?)?.let { authorityNames(it.roles) }
    }

    private fun authorityNames(roles: Set<EasyGrantedAuthority>) = roles.map { it.authority }.sorted()

    // --- what the normalisation accepts ----------------------------------------------------------

    @Test
    fun `a comma-separated string is several roles, on both paths`() {
        assertEquals(listOf("ROLE_STUDENT", "ROLE_TEACHER"), viaHeader("student,teacher"))
        assertEquals(listOf("ROLE_STUDENT", "ROLE_TEACHER"), viaJwt("student,teacher"))
    }

    @Test
    fun `whitespace around a separator is not part of the role`() {
        // This was a 500 on the header path: `student, teacher` split to " teacher", matched nothing,
        // and threw out of a filter that did not catch.
        assertEquals(listOf("ROLE_STUDENT", "ROLE_TEACHER"), viaHeader("student, teacher"))
        assertEquals(listOf("ROLE_ADMIN"), viaHeader("  admin  "))
        assertEquals(listOf("ROLE_STUDENT", "ROLE_TEACHER"), viaJwt("student, teacher"))
    }

    @Test
    fun `a stray separator does not become a role`() {
        // Dropping an empty fragment is not dropping a role — see normaliseRoleStrings. A trailing
        // comma is an artefact of the separator, not a claim about what the user may do.
        assertEquals(listOf("ROLE_STUDENT"), viaHeader("student,"))
        assertEquals(listOf("ROLE_STUDENT"), viaHeader(",student"))
        assertEquals(listOf("ROLE_STUDENT"), viaJwt("student,,"))
    }

    @Test
    fun `an array element is normalised too, not only a string that had to be split`() {
        // The array is the production shape and had no normalisation at all.
        assertEquals(listOf("ROLE_STUDENT", "ROLE_TEACHER"), viaJwt(listOf("student", " teacher")))
    }

    @Test
    fun `normalisation is inside the mapper, not only in front of it`() {
        // So a future third caller cannot get the old bug back by forgetting to normalise first.
        assertEquals(listOf("ROLE_TEACHER"), authorityNames(mapRoleStringsToRoles(listOf(" teacher"))))
        assertEquals(emptyList<String>(), normaliseRoleStrings(listOf("  ", ",", " , ")))
    }

    // --- what both paths refuse ------------------------------------------------------------------

    @Test
    fun `a value carrying no role authenticates nobody, on either path`() {
        // `getOptionalHeader` nulls only *blank* values, so `","` reached the filter and used to build
        // an authenticated principal with zero authorities — cleared `anyRequest().authenticated()`
        // and was then 403'd by every `@Secured` method, with nothing in the log saying why.
        assertNull(viaHeader(","))
        assertNull(viaHeader(" , "))
        assertNull(viaJwt(","))
        assertNull(viaJwt(emptyList<String>()))
        assertNull(viaJwt(""))
    }

    @Test
    fun `an unrecognised role authenticates nobody, on either path`() {
        // The header path used to let this escape as a 500: the filter sits ahead of
        // ExceptionTranslationFilter, which only translates authentication and access-denied
        // exceptions, and EasyExceptionHandler is DispatcherServlet-scoped. Now it declines to
        // authenticate and the chain answers 401, which is what the JWT path already did.
        assertNull(viaHeader("student,wizard"))
        assertNull(viaHeader("wizard"))
        assertNull(viaJwt("student,wizard"))
    }

    @Test
    fun `the mapper itself still throws rather than dropping a role it cannot map`() {
        // The two paths turn this into "no authentication"; the mapper must keep saying why, because
        // silently ignoring a role would quietly change what a user can do.
        assertThrows(RuntimeException::class.java) { mapRoleStringsToRoles(listOf("wizard")) }
    }

    // --- the actual finding ----------------------------------------------------------------------

    @Test
    fun bothPathsAgree() {
        // One value, both real parses, same answer — including the values where the answer is "nobody".
        // Any future change that touches one path and not the other fails here rather than in
        // production, where the symptom was a 401 meaning "your realm joined the roles with a comma".
        val values = listOf(
            "student",
            "teacher",
            "admin",
            "student,teacher",
            "student, teacher",
            "admin,student,teacher",
            "  teacher  ",
            "student,",
            ",",
            "student,wizard",
            "wizard",
        )
        values.forEach { value ->
            assertEquals(viaHeader(value), viaJwt(value), "the two role-parsing paths disagree about '$value'")
        }
    }
}
