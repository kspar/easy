package core.conf.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException

/**
 * Unit tests for the claim mapping in [EasyUserJwtConverter].
 *
 * Deliberately holds no Spring context, no database and no IdP, so these run anywhere —
 * including in CI, where the rest of the test suite is currently excluded because it needs
 * PostgreSQL and a gitignored config file (see EZ-1715).
 *
 * What this covers is the part that is *our* logic: claims in, [EasyUser] out. Signature,
 * issuer and expiry validation belong to Spring Security's JwtDecoder and are already tested
 * upstream; `core/dev-idp/` exercises those end to end against a real JWKS by hand.
 */
class EasyUserJwtConverterTest {

    private val converter = EasyUserJwtConverter()

    private fun jwt(claims: Map<String, Any>): Jwt =
        Jwt.withTokenValue("irrelevant")
            .header("alg", "RS256")
            .also { builder -> claims.forEach { (name, value) -> builder.claim(name, value) } }
            .build()

    private val fullClaims = mapOf(
        "preferred_username" to "dev-teacher",
        "email" to "teacher@test.ee",
        "given_name" to "Mari",
        "family_name" to "Maasikas",
        "easy_role" to listOf("teacher"),
    )

    @Test
    fun `maps every claim onto EasyUser`() {
        val user = converter.convert(jwt(fullClaims)) as EasyUser

        assertEquals("dev-teacher", user.id)
        assertEquals("teacher@test.ee", user.email)
        assertEquals("Mari", user.givenName)
        assertEquals("Maasikas", user.familyName)
        assertEquals(setOf(EasyGrantedAuthority(EasyRole.TEACHER)), user.roles)
        // getPrincipal() is what @PreAuthorize and the services see as the caller
        assertEquals("dev-teacher", user.principal)
        assertTrue(user.isAuthenticated)
    }

    @Test
    fun `maps all three roles and exposes them through the helpers`() {
        val user = converter.convert(
            jwt(fullClaims + ("easy_role" to listOf("student", "teacher", "admin")))
        ) as EasyUser

        assertTrue(user.isStudent())
        assertTrue(user.isTeacher())
        assertTrue(user.isAdmin())
    }

    @Test
    fun `accepts easy_role as a bare string rather than an array`() {
        // Keycloak sends an array, but a realm configured with a single-valued mapper sends a
        // string. Locking those users out over realm config would be a poor trade.
        val user = converter.convert(jwt(fullClaims + ("easy_role" to "student"))) as EasyUser

        assertEquals(setOf(EasyGrantedAuthority(EasyRole.STUDENT)), user.roles)
        assertTrue(user.isStudent())
    }

    @Test
    fun `tolerates absent given and family name`() {
        val user = converter.convert(
            jwt(fullClaims - "given_name" - "family_name")
        ) as EasyUser

        assertNull(user.givenName)
        assertNull(user.familyName)
    }

    @Test
    fun `rejects a token with no username`() {
        assertRejects(fullClaims - "preferred_username", "preferred_username")
    }

    @Test
    fun `rejects a token with no email`() {
        assertRejects(fullClaims - "email", "email")
    }

    @Test
    fun `rejects a token with no roles`() {
        assertRejects(fullClaims - "easy_role", "easy_role")
    }

    @Test
    fun `rejects an empty easy_role array, which is not the same as an absent one`() {
        // The gap the null check leaves. `easy_role: []` is not null, so it passed the guard above,
        // `mapRoleStringsToRoles(emptyList())` returned an empty set without throwing, and
        // `EasyUser.init` then marked the token authenticated — an authenticated principal with zero
        // authorities, which clears `anyRequest().authenticated()` and is refused 403 by every
        // `@Secured` method.
        //
        // That is the outcome this class's own docblock says it exists to avoid: a token from our IdP
        // carrying nothing usable means the realm's claim mappers are misconfigured, and "invalid
        // token" says so where a 403 three layers later does not. It also puts the empty array with
        // its siblings — an absent claim and an unmappable role are both already 401.
        assertRejects(fullClaims + ("easy_role" to emptyList<String>()), "easy_role")
    }

    @Test
    fun `an easy_role of one empty string is refused the same way an empty array is`() {
        // Adjacent shape, and it reaches the same place by a longer route: `getClaimAsStringList`
        // returns null for a non-list claim, so this takes the bare-string fallback, and
        // normalisation turns `""` into nothing at all — so the guard above sees an empty list and
        // says so. It used to arrive as `listOf("")` and be refused by the role mapper instead, with
        // "unmapped role ''", which blamed a role name nobody had written.
        assertRejects(fullClaims + ("easy_role" to ""), "easy_role")
    }

    @Test
    fun `a comma-separated easy_role string is several roles, as it always was in a dev header`() {
        // The asymmetry. A realm whose mapper emits the roles joined into one string used to get 401
        // "unmapped role student,teacher" here, while the identical value in `oidc_claim_easy_role`
        // authenticated as two roles. See RoleParsingTest.bothPathsAgree.
        val user = converter.convert(jwt(fullClaims + ("easy_role" to "student,teacher"))) as EasyUser
        assertEquals(setOf("ROLE_STUDENT", "ROLE_TEACHER"), user.authorities.map { it.authority }.toSet())
    }

    @Test
    fun `whitespace around a comma in easy_role is not part of the role`() {
        val user = converter.convert(jwt(fullClaims + ("easy_role" to "student, teacher"))) as EasyUser
        assertEquals(setOf("ROLE_STUDENT", "ROLE_TEACHER"), user.authorities.map { it.authority }.toSet())
    }

    @Test
    fun `whitespace inside an easy_role array element is not part of the role either`() {
        // The array is the production shape and had no normalisation at all.
        val user = converter.convert(jwt(fullClaims + ("easy_role" to listOf("student", " teacher")))) as EasyUser
        assertEquals(setOf("ROLE_STUDENT", "ROLE_TEACHER"), user.authorities.map { it.authority }.toSet())
    }

    @Test
    fun `names every missing claim at once, rather than only the first`() {
        val e = assertThrows(InvalidBearerTokenException::class.java) {
            converter.convert(jwt(fullClaims - "email" - "easy_role"))
        }
        assertTrue(e.message!!.contains("email"), e.message)
        assertTrue(e.message!!.contains("easy_role"), e.message)
    }

    @Test
    fun `rejects an unmapped role as an invalid token, not a server error`() {
        // Before core verified tokens itself this threw a bare RuntimeException, i.e. a 500.
        // A role we cannot map is a bad token, and 401 says so.
        val e = assertThrows(InvalidBearerTokenException::class.java) {
            converter.convert(jwt(fullClaims + ("easy_role" to listOf("teacher", "wizard"))))
        }
        assertTrue(e.cause is RuntimeException)
    }

    private fun assertRejects(claims: Map<String, Any>, expectedInMessage: String) {
        val e = assertThrows(InvalidBearerTokenException::class.java) {
            converter.convert(jwt(claims))
        }
        assertTrue(
            e.message!!.contains(expectedInMessage),
            "Expected the error to name '$expectedInMessage', got: ${e.message}",
        )
    }
}
