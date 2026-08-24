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
 *
 * **Keep these header values ASCII.** Spring's `StrictHttpFirewall` rejects a request whose header
 * value contains a control character, and a non-ASCII name typed into `curl` becomes one: request
 * headers are decoded as ISO-8859-1, so the UTF-8 bytes of `Ü` (`0xC3 0x9C`) arrive as `Ã` followed
 * by U+009C — a C1 control. The request is refused before reaching any of this, with a 400 that says
 * nothing about names.
 *
 * That is the whole of EZ-1434, which was worked around by switching the firewall's header-value
 * check off globally. The workaround is gone (see [SecurityConf]), because turning a production
 * control off for a testing convenience is the wrong trade and the names here are arbitrary anyway:
 * a test user called `Ulo` proves everything a test user called `Ülo` would. If a non-ASCII display
 * name is ever genuinely needed for a test, send it as a JWT claim rather than a header.
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