package core.conf.security

import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.GrantedAuthority


class EasyUser(
    val id: String,
    val email: String,
    val givenName: String?,
    val familyName: String?,
    val roles: Set<EasyGrantedAuthority>,
) : AbstractAuthenticationToken(roles) {

    // Safe to set: this object is only constructed after the required claims have been
    // validated — from a signature-verified JWT in production (EasyUserJwtConverter), or
    // from request headers when auth is disabled for local dev (DummyZeroAuthFilter).
    // Required by Spring Security 7 which strictly checks isAuthenticated() == true.
    init {
        isAuthenticated = true
    }
    // We have no credentials
    override fun getCredentials(): Any? = null

    override fun getPrincipal(): Any = id

    fun isStudent(): Boolean = roles.contains(EasyGrantedAuthority(EasyRole.STUDENT))

    fun isTeacher(): Boolean = roles.contains(EasyGrantedAuthority(EasyRole.TEACHER))

    fun isAdmin(): Boolean = roles.contains(EasyGrantedAuthority(EasyRole.ADMIN))
}

class EasyGrantedAuthority(private val role: EasyRole) : GrantedAuthority {

    override fun getAuthority(): String = role.roleWithPrefix

    override fun equals(other: Any?): Boolean {
        return if (other is EasyGrantedAuthority) {
            role == other.role
        } else {
            false
        }
    }

    override fun hashCode(): Int {
        return role.hashCode()
    }
}

enum class EasyRole(val roleWithPrefix: String) {
    STUDENT("ROLE_STUDENT"),
    TEACHER("ROLE_TEACHER"),
    ADMIN("ROLE_ADMIN")
}

/**
 * The one place claim text becomes a list of role names.
 *
 * Roles arrive in two shapes — a JSON array in the `easy_role` JWT claim, and a comma-separated
 * string in the `oidc_claim_easy_role` header the auth-disabled dev path uses — and each shape used
 * to be parsed by its own code. They disagreed: the header split on comma, the JWT single-string
 * fallback wrapped the whole value as one role, so a realm emitting `easy_role: "student,teacher"`
 * as one string was rejected in production and accepted in dev. Neither trimmed, so `student, teacher`
 * in a header threw out of a filter that does not catch, i.e. a 500.
 *
 * So: every element may itself be a comma-separated list, whitespace around a separator is not part
 * of a role, and both callers come through here. `RoleParsingTest.bothPathsAgree` is what keeps them
 * from drifting apart again.
 *
 * **Dropping an empty fragment is not the same as dropping a role**, which matters because
 * [mapRoleStringsToRoles] deliberately throws rather than ignoring what it cannot map. A trailing
 * comma is an artefact of the separator and carries no claim about what the user may do; `wizard` is a
 * claim we cannot honour and still fails. A value that normalises to nothing returns an empty list,
 * which is what lets [EasyUserJwtConverter] report an empty `easy_role` rather than blaming a role
 * named `""`.
 */
fun normaliseRoleStrings(roleStrings: List<String>): List<String> =
    roleStrings.flatMap { it.split(",") }
        .map(String::trim)
        .filter { it.isNotEmpty() }

// There was a `mapHeaderToRoles(header)` here, `mapRoleStringsToRoles(normaliseRoleStrings(listOf(h)))`
// in one call. [DummyZeroAuthFilter] no longer uses it — it needs the normalised list in its own hand
// to reject a header that carries no role — so the function's only remaining callers were tests, which
// made it a test-only paraphrase of the dev path rather than the dev path. That is the same drift this
// file exists to prevent, one level up: a test exercising a convenience wrapper proves nothing about
// the caller that no longer uses it. `RoleParsingTest` drives the filter and the converter directly.

/**
 * Throws rather than dropping anything: a role we cannot map is a Keycloak configuration problem, and
 * silently ignoring it would quietly change what a user can do.
 *
 * Normalises its input, so a caller that has not already done so still gets trimming and comma
 * splitting, and the second throw keeps that from being a downgrade: without it,
 * `mapRoleStringsToRoles(listOf(","))` would *return an empty set* where it used to throw
 * `Unmapped role ""`, turning the loudest available signal into the quiet failure this area has now
 * produced twice — an authenticated principal with no authorities.
 *
 * **Scope that honestly, though: this does not decide whether "no roles" is allowed, and cannot.**
 * The guard is keyed on the raw argument, so it fires for `mapRoleStringsToRoles(listOf(","))` and
 * not for `mapRoleStringsToRoles(normaliseRoleStrings(listOf(",")))` — which is `emptyList()` by then,
 * and an empty request legitimately maps to an empty set. Both real callers take that second shape,
 * because both need the emptiness answer *before* mapping in order to say which claim was missing, so
 * both do their own check and neither is protected by this one. A third caller written in the same
 * style would not be either. Refusing an authority-less principal is the caller's job, and there is no
 * version of this function that can take it over while the callers still need to name what was absent.
 */
fun mapRoleStringsToRoles(roleStrings: List<String>): Set<EasyGrantedAuthority> {
    val normalised = normaliseRoleStrings(roleStrings)
    if (normalised.isEmpty() && roleStrings.isNotEmpty()) {
        throw RuntimeException("No role in $roleStrings")
    }
    return normalised.map {
        when (it) {
            "student" -> EasyGrantedAuthority(EasyRole.STUDENT)
            "teacher" -> EasyGrantedAuthority(EasyRole.TEACHER)
            "admin" -> EasyGrantedAuthority(EasyRole.ADMIN)
            else -> throw RuntimeException("Unmapped role $it")
        }
    }.toSet()
}
