package core.conf.security

import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.authentication.preauth.RequestHeaderAuthenticationFilter
import org.springframework.security.web.firewall.HttpFirewall
import org.springframework.security.web.firewall.StrictHttpFirewall
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse


@Configuration
@EnableWebSecurity
@EnableGlobalMethodSecurity(securedEnabled = true)
class SecurityConf : WebSecurityConfigurerAdapter() {
    private val log = KotlinLogging.logger {}

    @Value("\${easy.core.auth-enabled}")
    private var authEnabled: Boolean = true

    override fun configure(http: HttpSecurity) {
        http.authorizeRequests()
                // All services require auth == any role by default
                .anyRequest().authenticated()

        http.addFilterAfter(
                if (authEnabled) PreAuthHeaderFilter() else DummyZeroAuthFilter(),
                RequestHeaderAuthenticationFilter::class.java)

        http.exceptionHandling()
                .accessDeniedHandler { request, response, _ ->
                    log.info { "Forbidden for ${makeRequestLogMsg(request)}" }
                    response.sendError(HttpServletResponse.SC_FORBIDDEN)
                }
                .authenticationEntryPoint { request, response, _ ->
                    log.info { "Unauthorized for ${makeRequestLogMsg(request)}" }
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED)
                }

        http.sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)

        http.cors().configurationSource(getCorsConfiguration())
        http.csrf().disable()
    }

    override fun configure(auth: AuthenticationManagerBuilder) {
        auth.authenticationProvider(EasyUserAuthProvider())
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
        conf.allowedOrigins = listOf("http://localhost:63341", "https://lahendus.ut.ee", "https://dev.lahendus.ut.ee")
        conf.allowedMethods = listOf("GET", "POST", "DELETE", "PUT", "PATCH")
        conf.allowedHeaders = listOf("Authorization", "Cache-Control", "Content-Type")
        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", conf)
        return source
    }
}
