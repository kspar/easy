package core.conf.security

import com.auth0.jwt.JWT
import mu.KotlinLogging
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import javax.servlet.FilterChain
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

private val log = KotlinLogging.logger {}


class PreAuthHeaderFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val username = getOptionalHeader("oidc_claim_preferred_username", request)
        val moodleUsername = getOptionalHeader("oidc_claim_ut_uid", request)
        val email = getOptionalHeader("oidc_claim_email", request)
        val givenName = getOptionalHeader("oidc_claim_given_name", request)
        val familyName = getOptionalHeader("oidc_claim_family_name", request)
        val roles = getOptionalHeader("oidc_claim_easy_role", request)

        val token = getOptionalHeader("OIDC_access_token", request)
        log.debug { "token: $token" }
        val jwt = JWT.decode(token)
        val sub = jwt.subject
        log.debug { "sub: $sub" }
        jwt.claims.forEach {
            log.debug { "claim: ${it.key}: ${it.value}" }
        }

        if (username != null
                && email != null
                && givenName != null
                && familyName != null
                && roles != null) {

            val user = EasyUser(username, email, givenName, familyName, mapHeaderToRoles(roles), moodleUsername)
            SecurityContextHolder.getContext().authentication = user
        }

        filterChain.doFilter(request, response)
    }

    private fun getOptionalHeader(headerName: String, request: HttpServletRequest): String? {
        val headerValue: String? = request.getHeader(headerName)
        return if (headerValue.isNullOrBlank()) null else headerValue
    }

    private fun mapHeaderToRoles(rolesHeader: String): Set<EasyGrantedAuthority> =
            rolesHeader.split(",")
                    .map {
                        when (it) {
                            "student" -> EasyGrantedAuthority(EasyRole.STUDENT)
                            "teacher" -> EasyGrantedAuthority(EasyRole.TEACHER)
                            "admin" -> EasyGrantedAuthority(EasyRole.ADMIN)
                            else -> throw RuntimeException("Unmapped role $it")
                        }
                    }
                    .toSet()
}
