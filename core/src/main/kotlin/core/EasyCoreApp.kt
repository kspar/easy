package core

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration
import org.springframework.scheduling.annotation.EnableAsync
import java.util.TimeZone

/**
 * [UserDetailsServiceAutoConfiguration] is excluded because core has no username-and-password
 * authentication and never will: identity comes from a Keycloak JWT, or from `oidc_claim_*` headers
 * on the auth-disabled dev path. Left enabled, it creates an `InMemoryUserDetailsManager` and logs
 * "Using generated security password: <uuid>" at startup.
 *
 * Stated here rather than left to chance, which is what it was. Its condition is
 * `@ConditionalOnMissingBean({AuthenticationManager, AuthenticationProvider, UserDetailsService,
 * AuthenticationManagerResolver}, type = "…JwtDecoder")`, and it had been suppressed only
 * incidentally — by an `authenticationManager` bean and an `EasyUserAuthProvider` that nothing
 * invoked and which were deleted for being dead. That left a `JwtDecoder` as the only thing holding
 * it back, and no such bean exists when `easy.core.auth-enabled` is false, since the resource server
 * is built inside that branch. So deleting two beans nobody called would have switched on a security
 * autoconfiguration nobody wanted, in exactly the mode where it is hardest to notice.
 *
 * Not a bypass either way — no `httpBasic` or `formLogin` is configured, so the generated user has no
 * entry point. The reason to be explicit is that "no password auth" is a decision, and a decision
 * that depends on an unrelated bean existing is not being made anywhere.
 */
@EnableAsync
@SpringBootApplication(exclude = [UserDetailsServiceAutoConfiguration::class])
class EasyCoreApp

fun main(args: Array<String>) {
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    runApplication<EasyCoreApp>(*args)
}
