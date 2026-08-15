package core.testing

import core.conf.security.EasyGrantedAuthority
import core.conf.security.EasyRole
import core.conf.security.EasyUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.request.RequestPostProcessor

/**
 * Callers, for MockMvc tests.
 *
 * These put a real [EasyUser] into the `SecurityContext` — the *same object* both production auth
 * paths construct. `EasyUserJwtConverter` builds one from a signature-verified JWT, and
 * `DummyZeroAuthFilter` builds one from request headers when auth is disabled for local dev. So a
 * test written against this exercises what the two have in common and neither one's plumbing, which
 * is the right level for everything except the token verification itself.
 *
 * Deliberately **not** the `oidc_claim_*` headers. Using those would need
 * `easy.core.auth-enabled: false` in the test config, and `SecurityConf` branches on exactly that
 * flag — so the test context would be wired differently from every deployed environment, and the
 * release gate would become the largest consumer of the one code path that must never run anywhere
 * real. See `doc/core/api-testing.md`.
 *
 * Anonymous is the absence of these: pass no post-processor at all.
 */
object Auth {

    const val STUDENT_ID = "test-student"
    const val TEACHER_ID = "test-teacher"
    const val ADMIN_ID = "test-admin"

    fun asStudent(id: String = STUDENT_ID): RequestPostProcessor = asRoles(id, EasyRole.STUDENT)
    fun asTeacher(id: String = TEACHER_ID): RequestPostProcessor = asRoles(id, EasyRole.TEACHER)

    /**
     * An admin. Note this carries *only* `ROLE_ADMIN`, not the three roles a real admin account
     * often holds — `kspar` in the test data is student, teacher and admin at once. Keeping them
     * separate is what lets the authorization matrix ask a precise question: an endpoint that is
     * `@Secured("ROLE_TEACHER")` must reject this caller, and it cannot if "admin" silently implies
     * teacher.
     */
    fun asAdmin(id: String = ADMIN_ID): RequestPostProcessor = asRoles(id, EasyRole.ADMIN)

    fun asRoles(id: String, vararg roles: EasyRole): RequestPostProcessor =
        authentication(easyUser(id, *roles))

    fun easyUser(id: String, vararg roles: EasyRole) = EasyUser(
        id = id,
        email = "$id@example.test",
        givenName = "Test",
        familyName = "Caller",
        roles = roles.map { EasyGrantedAuthority(it) }.toSet(),
    )
}
