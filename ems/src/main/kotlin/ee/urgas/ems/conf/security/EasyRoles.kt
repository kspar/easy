package ee.urgas.ems.conf.security

import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.GrantedAuthority


class EasyUser(val email: String, val givenName: String, val familyName: String, val roles: Set<EasyGrantedAuthority>) :
        AbstractAuthenticationToken(roles) {

    // We have no credentials
    override fun getCredentials(): Any? = null

    override fun getPrincipal(): Any = email
}

class EasyGrantedAuthority(private val role: EasyRole) : GrantedAuthority {
    override fun getAuthority(): String = role.roleWithPrefix
}

enum class EasyRole(val roleWithPrefix: String) {
    STUDENT("ROLE_STUDENT"),
    TEACHER("ROLE_TEACHER")
}
