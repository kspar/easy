package ee.urgas.aas.conf.security

import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.authentication.preauth.RequestHeaderAuthenticationFilter
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse


@Configuration
@EnableWebSecurity
@EnableGlobalMethodSecurity(securedEnabled = true)
class SecurityConf : WebSecurityConfigurerAdapter() {
    private val log = KotlinLogging.logger {}

    @Value("\${auth-enabled}")
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
        http.csrf().disable()
    }

    override fun configure(auth: AuthenticationManagerBuilder) {
        auth.authenticationProvider(EasyUserAuthProvider())
    }

    private fun makeRequestLogMsg(req: HttpServletRequest): String {
        val email = req.getHeader("oidc_claim_email")
        val role = req.getHeader("oidc_claim_easy_role")
        val ip = req.remoteAddr
        val method = req.method
        val url = req.requestURL
        return "$email with role $role from $ip: $method $url"
    }
}
