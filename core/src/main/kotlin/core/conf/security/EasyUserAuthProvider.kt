package core.conf.security

import org.springframework.context.annotation.Configuration
import org.springframework.security.authentication.AuthenticationProvider
import org.springframework.security.core.Authentication


@Configuration
class EasyUserAuthProvider : AuthenticationProvider {
    override fun authenticate(authentication: Authentication?): Authentication? {
        authentication?.isAuthenticated = authentication?.authorities?.isNotEmpty() ?: false
        return authentication
    }

    override fun supports(authentication: Class<*>?): Boolean {
        return if (authentication == null) {
            false
        } else {
            EasyUser::class.java.isAssignableFrom(authentication)
        }
    }
}
