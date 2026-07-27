package core.conf.security

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.http.HttpServletRequest
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
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.preauth.RequestHeaderAuthenticationFilter
import org.springframework.security.web.firewall.HttpFirewall
import org.springframework.security.web.firewall.StrictHttpFirewall
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource


@Configuration
@EnableWebSecurity
@EnableMethodSecurity(securedEnabled = true)
class SecurityConf {
    private val log = KotlinLogging.logger {}

    @Value("\${easy.core.auth-enabled}")
    private var authEnabled: Boolean = true

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
            }.addFilterAfter(
                if (authEnabled) PreAuthHeaderFilter() else DummyZeroAuthFilter(),
                RequestHeaderAuthenticationFilter::class.java
            ).exceptionHandling {
                it.accessDeniedHandler { request, response, _ ->
                    log.info { "Forbidden for ${makeRequestLogMsg(request)}" }
                    response.sendError(HttpServletResponse.SC_FORBIDDEN)
                }
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
        val username = req.getHeader("oidc_claim_preferred_username")
        val role = req.getHeader("oidc_claim_easy_role")
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
