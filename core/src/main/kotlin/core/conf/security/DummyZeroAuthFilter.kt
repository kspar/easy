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

        // Both failure shapes leave the context unset, so the request continues as anonymous and the
        // chain answers 401 — the same answer the JWT path gives for the same input. Neither is
        // reachable in a deployed environment, since this filter only exists when auth is disabled,
        // but both used to produce something worse than a 401 here:
        //
        //  - roles that normalise to nothing. `getOptionalHeader` nulls only *blank* values, so
        //    `oidc_claim_easy_role: ","` passed the null check, normalised to an empty list, and built
        //    an authenticated principal with zero authorities — 403 from every `@Secured` method, with
        //    nothing in the log naming the cause.
        //  - a role we cannot map. `mapRoleStringsToRoles` throws, and this filter sits at the
        //    pre-auth position ahead of `ExceptionTranslationFilter`, which only translates
        //    authentication and access-denied exceptions; `EasyExceptionHandler` is
        //    DispatcherServlet-scoped and never sees it. So it left the container to answer 500.
        val roleStrings = roles?.let { normaliseRoleStrings(listOf(it)) }

        if (username != null && email != null && !roleStrings.isNullOrEmpty()) {
            val mappedRoles = try {
                mapRoleStringsToRoles(roleStrings)
            } catch (e: RuntimeException) {
                logger.warn("Unmappable oidc_claim_easy_role '$roles' for '$username': ${e.message}")
                null
            }
            if (mappedRoles != null) {
                val context = SecurityContextHolder.createEmptyContext()
                context.authentication = EasyUser(username, email, givenName, familyName, mappedRoles)
                SecurityContextHolder.setContext(context)
                securityContextRepository.saveContext(context, request, response)
            }
        }

        filterChain.doFilter(request, response)
    }
}