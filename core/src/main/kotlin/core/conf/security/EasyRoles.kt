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
 * Roles arrive as a JSON array in the `easy_role` JWT claim, but as a comma-separated string
 * in the `oidc_claim_easy_role` header the auth-disabled dev path uses.
 */
fun mapHeaderToRoles(rolesHeader: String): Set<EasyGrantedAuthority> =
    mapRoleStringsToRoles(rolesHeader.split(","))

/**
 * Throws on an unrecognised role rather than dropping it: a role we cannot map is a Keycloak
 * configuration problem, and silently ignoring it would quietly change what a user can do.
 */
fun mapRoleStringsToRoles(roleStrings: List<String>): Set<EasyGrantedAuthority> =
    roleStrings.map {
        when (it) {
            "student" -> EasyGrantedAuthority(EasyRole.STUDENT)
            "teacher" -> EasyGrantedAuthority(EasyRole.TEACHER)
            "admin" -> EasyGrantedAuthority(EasyRole.ADMIN)
            else -> throw RuntimeException("Unmapped role $it")
        }
    }.toSet()
