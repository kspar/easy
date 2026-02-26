package core.conf.security

import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.GrantedAuthority


class EasyUser(
    val oldId: String,
    val id: String,
    val email: String,
    val givenName: String?,
    val familyName: String?,
    val roles: Set<EasyGrantedAuthority>,
) : AbstractAuthenticationToken(roles) {

    // Safe to set: authentication is guaranteed by the filter logic that constructs
    // this object only after validating required claims (username, email, roles).
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
