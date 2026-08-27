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

    /** What the dev header filter did with a given `oidc_claim_easy_role` value. */
    private class HeaderOutcome(val roles: List<String>?, val status: Int, val continued: Boolean)

    private fun runFilter(headers: Map<String, String>): HeaderOutcome {
        val request = MockHttpServletRequest("GET", "/v2/versions")
        headers.forEach { (name, value) -> request.addHeader(name, value) }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        SecurityContextHolder.clearContext()
        DummyZeroAuthFilter().doFilter(request, response, chain)
        val authentication = SecurityContextHolder.getContext().authentication as EasyUser?
        // `chain.request` is null until the chain is actually invoked, which is how "declined and
        // stopped" is told apart from "declined and passed on as anonymous" — the distinction that
        // decides whether a `permitAll` path answers 401 or 200.
        return HeaderOutcome(authentication?.let { authorityNames(it.roles) }, response.status, chain.request != null)
    }

    /** Roles the dev header path grants for an `oidc_claim_easy_role` value, or null if it refuses. */
    private fun viaHeader(header: String): List<String>? = runFilter(
        mapOf(
            "oidc_claim_preferred_username" to "dev",
            "oidc_claim_email" to "dev@test.ee",
            "oidc_claim_easy_role" to header,
        )
    ).roles

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
    fun `bad claim headers are refused outright, not passed on as anonymous`() {
        // The divergence a "leave the context unset" decline hides. On an authenticated endpoint both
        // look like 401, so it reads as parity with the JWT path — but continuing the chain means a
        // `permitAll` path (`/v2/unauth/articles/{id}`, an uploaded file) answers **200 anonymously**
        // for credentials that were offered and rejected, where the same bad token gets 401 in
        // production because `BearerTokenAuthenticationFilter` aborts. An attempt to authenticate that
        // fails is a failure on every path.
        val unmappable = runFilter(
            mapOf(
                "oidc_claim_preferred_username" to "dev",
                "oidc_claim_email" to "dev@test.ee",
                "oidc_claim_easy_role" to "wizard",
            )
        )
        assertEquals(401, unmappable.status)
        assertEquals(false, unmappable.continued) { "the request was passed on and would 200 on permitAll" }

        val noRole = runFilter(
            mapOf(
                "oidc_claim_preferred_username" to "dev",
                "oidc_claim_email" to "dev@test.ee",
                "oidc_claim_easy_role" to ",",
            )
        )
        assertEquals(401, noRole.status)
        assertEquals(false, noRole.continued)

        // An incomplete set is also an attempt, so it is refused rather than downgraded.
        val partial = runFilter(mapOf("oidc_claim_preferred_username" to "dev"))
        assertEquals(401, partial.status)
        assertEquals(false, partial.continued)
    }

    @Test
    fun `claim headers sent empty are a failed login, not an anonymous request`() {
        // The distinction has to be drawn on header *presence*, not on the blank-nulled values:
        // `getOptionalHeader` nulls a blank, so testing those classified "every claim header sent
        // empty" as anonymous — credentials offered, unusable, and 200 on a `permitAll` path.
        // `EMS.postman_collection.json` sends these as `{{…}}` variables, so running it against an
        // unset environment produces exactly this request.
        val allEmpty = runFilter(
            mapOf(
                "oidc_claim_preferred_username" to "",
                "oidc_claim_email" to "",
                "oidc_claim_easy_role" to "",
            )
        )
        assertEquals(401, allEmpty.status)
        assertEquals(false, allEmpty.continued) { "empty claim headers were treated as anonymous" }

        // And a request carrying only an optional name is still an attempt, so it is refused rather
        // than downgraded — all five header names count, not just the three required ones.
        val onlyGivenName = runFilter(mapOf("oidc_claim_given_name" to "Ulo"))
        assertEquals(401, onlyGivenName.status)
        assertEquals(false, onlyGivenName.continued)
    }

    @Test
    fun `no claim headers at all is an anonymous request, which still reaches permitAll`() {
        // The other half, and why the filter cannot simply refuse everything it does not like: a
        // request offering no credentials is not a failed login, and a public endpoint must still serve
        // it. This is the case that keeps the refusal above from breaking the anonymous surface.
        val anonymous = runFilter(emptyMap())

        assertEquals(true, anonymous.continued) { "an anonymous request must reach the rest of the chain" }
        assertNull(anonymous.roles)
        assertEquals(200, anonymous.status)
    }

    @Test
    fun `the mapper itself still throws rather than dropping a role it cannot map`() {
        // The two paths turn this into "no authentication"; the mapper must keep saying why, because
        // silently ignoring a role would quietly change what a user can do.
        assertThrows(RuntimeException::class.java) { mapRoleStringsToRoles(listOf("wizard")) }
    }

    @Test
    fun `input that carries something but yields no role is an error, not an empty answer`() {
        // The trap in normalising inside the mapper. Normalisation drops empty fragments, so without
        // this guard `mapRoleStringsToRoles(listOf(","))` would *return an empty set* where it used to
        // throw — turning the loudest signal available into the quiet failure this area has produced
        // twice already, an authenticated principal with no authorities. A third caller writing the
        // obvious `if (roleStrings.isNotEmpty()) EasyUser(..., mapRoleStringsToRoles(roleStrings))`
        // must not be able to build one.
        assertThrows(RuntimeException::class.java) { mapRoleStringsToRoles(listOf(",")) }
        assertThrows(RuntimeException::class.java) { mapRoleStringsToRoles(listOf("")) }
        assertThrows(RuntimeException::class.java) { mapRoleStringsToRoles(listOf(" ", " , ")) }

        // Asking about no roles is still no roles: whether that is allowed is the caller's decision,
        // and both callers reject it.
        assertEquals(emptySet<EasyGrantedAuthority>(), mapRoleStringsToRoles(emptyList()))
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
