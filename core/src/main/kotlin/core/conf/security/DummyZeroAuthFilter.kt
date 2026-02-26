package core.conf.security

import core.ems.service.getOptionalHeader
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter


class DummyZeroAuthFilter : OncePerRequestFilter() {

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

        if (username != null &&
            email != null &&
            roles != null
        ) {
            SecurityContextHolder.getContext().authentication = EasyUser(
                username, username, email, givenName, familyName, mapHeaderToRoles(roles)
            )
        }

        filterChain.doFilter(request, response)
    }
}
