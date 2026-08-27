package core.conf.security

import core.EasyCoreApp
import core.testing.IntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.security.authentication.AuthenticationProvider

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
@IntegrationTest
class NoPasswordAuthenticationTest(@Autowired private val context: ApplicationContext) {

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

    @Test
    fun `nothing registers an AuthenticationProvider`() {
        // The half of the replaced test that was *not* tautological, restored. The `JwtDecoder`
        // argument only vacated the `UserDetailsService` assertion: an `AuthenticationProvider` bean
        // would be a real change and this really does catch it.
        //
        // Why it is worth catching: re-adding one silently makes that bean, rather than the annotation
        // above, the thing suppressing `UserDetailsServiceAutoConfiguration` — and with
        // `auth-enabled: false` a provider like the deleted `EasyUserAuthProvider`, whose `authenticate`
        // set `isAuthenticated = false` for an empty role set, would reject users outright. That is the
        // failure SecurityConf's deletion note gives as the reason not to wire one up.
        val providers = context.getBeanNamesForType(AuthenticationProvider::class.java).toList()
        assertEquals(emptyList<String>(), providers) { "an AuthenticationProvider is registered: $providers" }
    }
}
