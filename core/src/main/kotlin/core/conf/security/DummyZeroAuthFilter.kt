package core.conf.security

import core.ems.service.getOptionalHeader
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.web.filter.OncePerRequestFilter


/**
 * Trusts `oidc_claim_*` request headers verbatim, so anything that can reach core can be any
 * user. Installed only when `easy.core.auth-enabled` is false — local dev and curl-based API
 * testing (doc/core/api-testing.md). Production verifies JWTs instead; see [EasyUserJwtConverter].
 */
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
                username, email, givenName, familyName, mapHeaderToRoles(roles)
            )
            SecurityContextHolder.setContext(context)
            securityContextRepository.saveContext(context, request, response)
        }

        filterChain.doFilter(request, response)
    }
}