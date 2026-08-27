package core.conf.security

import core.EasyCoreApp
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration

/**
 * That `EasyCoreApp` still excludes [UserDetailsServiceAutoConfiguration].
 *
 * Identity comes from a Keycloak JWT, or from `oidc_claim_*` headers on the auth-disabled dev path.
 * There is no third source and no username-and-password login. Left enabled, that autoconfiguration
 * builds an `InMemoryUserDetailsManager` and logs "Using generated security password: <uuid>" at
 * startup. Its condition is `@ConditionalOnMissingBean({AuthenticationManager, AuthenticationProvider,
 * UserDetailsService, AuthenticationManagerResolver}, type = "…JwtDecoder")`, and it had been held off
 * only incidentally — by an `authenticationManager` bean and an `EasyUserAuthProvider` that nothing
 * invoked and that were deleted for being dead.
 *
 * **This asserts the declaration, not the behaviour, and the difference is the reason this class
 * exists.** The first version of it asked the running context for `UserDetailsService` beans and found
 * none — which proved nothing: the integration context sets `auth-enabled: true` and a `jwk-set-uri`,
 * so a `JwtDecoder` bean exists and the autoconfiguration is inactive whether or not it is excluded.
 * Removing the `exclude` left that test green. The regression only happens when `auth-enabled` is
 * false, and no test context runs in that mode — one Spring context is a deliberate rule here, and a
 * second one to cover this would cost more than it is worth.
 *
 * So this reads the annotation. It cannot see the runtime effect, but it does fail if somebody deletes
 * the exclusion, which is the change actually worth catching. Written down rather than implied, since
 * the honest scope of a guard is part of the guard.
 */
class NoPasswordAuthenticationTest {

    @Test
    fun `the app excludes Boot's user-details autoconfiguration`() {
        val excluded = EasyCoreApp::class.java
            .getAnnotation(SpringBootApplication::class.java)
            .exclude
            .toList()

        assertTrue(excluded.contains(UserDetailsServiceAutoConfiguration::class)) {
            "EasyCoreApp no longer excludes UserDetailsServiceAutoConfiguration; excludes = $excluded"
        }
    }
}
