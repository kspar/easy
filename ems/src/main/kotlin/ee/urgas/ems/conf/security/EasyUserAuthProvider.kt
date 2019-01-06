package ee.urgas.ems.conf.security

import org.springframework.context.annotation.Configuration
import org.springframework.security.authentication.AuthenticationProvider
import org.springframework.security.core.Authentication


@Configuration
class EasyUserAuthProvider : AuthenticationProvider {
    override fun authenticate(authentication: Authentication?): Authentication? {
        authentication?.isAuthenticated = authentication?.authorities?.isNotEmpty() ?: false
        return authentication
    }

    override fun supports(authentication: Class<*>?): Boolean =
            EasyUser::class.java.isAssignableFrom(authentication)
}
