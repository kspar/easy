package core.conf.security

import core.ems.service.getOptionalHeader
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.web.filter.OncePerRequestFilter


class DummyZeroAuthFilter(private val securityContextRepository: SecurityContextRepository = RequestAttributeSecurityContextRepository()) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val username = request.getOptionalHeader("oidc_claim_preferred_username")
        val email = request.getOptionalHeader("oidc_claim_email")
        val givenName = request.getOptionalHeader("oidc_claim_given_name")
        val familyName = request.getOptionalHeader("oidc_claim_family_name")
        val roles = request.getOptionalHeader("oidc_claim_easy_role")

        if (username != null && email != null && roles != null) {
            val context = SecurityContextHolder.createEmptyContext()
            context.authentication = EasyUser(
                username, username, email, givenName, familyName, mapHeaderToRoles(roles)
            )
            SecurityContextHolder.setContext(context)
            securityContextRepository.saveContext(context, request, response)
        }

        filterChain.doFilter(request, response)
    }
}