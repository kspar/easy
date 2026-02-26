package core.conf.security

import org.springframework.security.authentication.AuthenticationProvider
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component


@Component
class EasyUserAuthProvider : AuthenticationProvider {
    override fun authenticate(authentication: Authentication): Authentication? {
        authentication.isAuthenticated = authentication.authorities?.isNotEmpty() ?: false
        return authentication
    }

    override fun supports(authentication: Class<*>): Boolean {
        return EasyUser::class.java.isAssignableFrom(authentication)
    }
}
