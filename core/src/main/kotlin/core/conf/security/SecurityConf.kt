package core.conf.security

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import jakarta.servlet.http.HttpServletRequest
import java.net.InetAddress
import java.net.UnknownHostException
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.preauth.RequestHeaderAuthenticationFilter
import org.springframework.security.web.firewall.HttpFirewall
import org.springframework.security.web.firewall.StrictHttpFirewall
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource


/**
 * Whether a `server.address` value binds to loopback only.
 *
 * Fails closed on both edges: a blank value means Tomcat binds every interface, and an address
 * that will not resolve is assumed to be routable. Top-level rather than private so the
 * decision can be unit tested without standing up a Spring context.
 */
internal fun isLoopbackAddress(address: String): Boolean {
    if (address.isBlank()) return false
    return try {
        InetAddress.getByName(address).isLoopbackAddress
    } catch (_: UnknownHostException) {
        false
    }
}

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(securedEnabled = true)
class SecurityConf {
    private val log = KotlinLogging.logger {}

    @Value("\${easy.core.auth-enabled}")
    private var authEnabled: Boolean = true

    @Value("\${server.address:}")
    private var serverAddress: String = ""

    /**
     * Refuses to start a core that both disables auth and listens beyond loopback.
     *
     * With `auth-enabled: false`, [DummyZeroAuthFilter] trusts `oidc_claim_*` headers verbatim,
     * so anything that can reach the port is any user it likes, admin included. That is fine
     * bound to 127.0.0.1 and catastrophic bound to 0.0.0.0 — including on a laptop sharing a
     * café network. Documenting the footgun in three places did not stop it from being a
     * footgun, so this fails closed instead, in the same spirit as the test suite's
     * `assertDisposableDatabase()`.
     *
     * Note that an unset `server.address` means "all interfaces", so a blank value is a
     * failure, not a default. Any address we cannot resolve is treated as non-loopback.
     */
    @PostConstruct
    fun assertAuthDisabledOnlyOnLoopback() {
        if (authEnabled) return

        val address = serverAddress.trim()
        if (!isLoopbackAddress(address)) throw IllegalStateException(
            """
            Refusing to start: easy.core.auth-enabled is false, but server.address is
            '${address.ifBlank { "<unset, meaning all interfaces>" }}'. Auth-disabled core trusts
            oidc_claim_* request headers, so on a non-loopback address anyone who can reach the
            port can act as any user, including admin.

            Local dev: add `server.address: 127.0.0.1` to application.yaml (the sample has it).
            Deployed environments: set easy.core.auth-enabled to true.
            """.trimIndent()
        )
    }

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain =
        http
            .authorizeHttpRequests {
                it.requestMatchers(
                    // Allow unauthenticated access to anonymous auto-assess services
                    "/*/unauth/exercises/*/anonymous/autoassess",
                    "/*/unauth/exercises/*/anonymous/details"
                ).permitAll()
                    // All other services require auth == any role by default
                    .anyRequest().authenticated()
            }.let {
                // Production verifies JWTs itself. The auth-disabled path builds an EasyUser
                // straight from request headers so local dev and curl need no IdP — see
                // doc/core/api-testing.md. It must never be enabled on a deployed environment.
                if (authEnabled)
                    it.oauth2ResourceServer { rs ->
                        rs.jwt { jwt -> jwt.jwtAuthenticationConverter(EasyUserJwtConverter()) }
                    }
                else
                    it.addFilterAfter(DummyZeroAuthFilter(), RequestHeaderAuthenticationFilter::class.java)
            }.exceptionHandling {
                it.accessDeniedHandler { request, response, _ ->
                    log.info { "Forbidden for ${makeRequestLogMsg(request)}" }
                    response.sendError(HttpServletResponse.SC_FORBIDDEN)
                }
                // Set explicitly, so it wins over the resource server's Bearer entry point —
                // we keep the logging, and give up the WWW-Authenticate header the SPA ignores.
                it.authenticationEntryPoint { request, response, _ ->
                    log.info { "Unauthorized for ${makeRequestLogMsg(request)}" }
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED)
                }
            }.sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .cors { it.configurationSource(getCorsConfiguration()) }
            .csrf { it.disable() }.build()

    @Bean
    @Throws(Exception::class)
    fun authenticationManager(authenticationConfiguration: AuthenticationConfiguration): AuthenticationManager? {
        return authenticationConfiguration.getAuthenticationManager()
    }

    // Temporary workaround for EZ-1434
    @Bean
    fun getHttpFirewall(): HttpFirewall {
        val strictHttpFirewall = StrictHttpFirewall()
        strictHttpFirewall.setAllowedHeaderValues { true }
        return strictHttpFirewall
    }

    private fun makeRequestLogMsg(req: HttpServletRequest): String {
        // Read from the security context rather than the request: there are no oidc_claim_*
        // headers to read since core started verifying tokens itself. A 403 has an authenticated
        // user here; a 401 usually does not, and unidentified is the honest answer then.
        val user = SecurityContextHolder.getContext().authentication as? EasyUser
        val username = user?.id ?: "unauthenticated"
        val role = user?.roles?.joinToString(",") { it.authority } ?: "-"
        val ip = req.remoteAddr
        val method = req.method
        val url = req.requestURL
        return "$username with role $role from $ip: $method $url"
    }

    private fun getCorsConfiguration(): CorsConfigurationSource {
        val conf = CorsConfiguration()
        // TODO: from conf
        conf.allowedOrigins = listOf(
            "http://local.lahendus.ut.ee:8090",
            "http://localhost:63341",
            "https://lahendus.ut.ee",
            "https://dev.lahendus.ut.ee"
        )
        conf.allowedMethods = listOf("GET", "POST", "DELETE", "PUT", "PATCH")
        conf.allowedHeaders = listOf("Authorization", "Cache-Control", "Content-Type")
        conf.exposedHeaders = listOf("Content-Disposition")
        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", conf)
        return source
    }
}
