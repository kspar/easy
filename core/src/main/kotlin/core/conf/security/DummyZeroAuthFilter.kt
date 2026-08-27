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

        // No headers at all is not a failed login, it is an anonymous request: it falls through so a
        // `permitAll` path still works without credentials. Headers that are *present and unusable* are
        // a failed login, and that is answered 401 here rather than passed on — see [reject].
        if (username == null && email == null && roles == null) {
            filterChain.doFilter(request, response)
            return
        }

        val roleStrings = roles?.let { normaliseRoleStrings(listOf(it)) }

        if (username == null || email == null || roleStrings.isNullOrEmpty()) {
            // `getOptionalHeader` nulls only *blank* values, so `oidc_claim_easy_role: ","` used to
            // pass the null check, normalise to nothing, and build an authenticated principal with zero
            // authorities — 403 from every `@Secured` method with nothing in the log saying why. Naming
            // what is missing is the whole point; the JWT path says the same thing for the same input.
            val missing = buildList {
                if (username == null) add("oidc_claim_preferred_username")
                if (email == null) add("oidc_claim_email")
                if (roleStrings.isNullOrEmpty()) add("oidc_claim_easy_role")
            }
            return reject(response, "missing or empty claim headers: $missing")
        }

        val mappedRoles = try {
            mapRoleStringsToRoles(roleStrings)
        } catch (e: RuntimeException) {
            // Caught because this filter sits at the pre-auth position, ahead of
            // `ExceptionTranslationFilter` — which translates only authentication and access-denied
            // exceptions — while `EasyExceptionHandler` is DispatcherServlet-scoped and never sees a
            // throw from here. Uncaught, it left the container to answer 500.
            return reject(response, "unmappable oidc_claim_easy_role '$roles' for '$username': ${e.message}")
        }

        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = EasyUser(username, email, givenName, familyName, mappedRoles)
        SecurityContextHolder.setContext(context)
        securityContextRepository.saveContext(context, request, response)

        filterChain.doFilter(request, response)
    }

    /**
     * Answers 401 and stops, rather than continuing anonymously.
     *
     * Stopping is the part that matters. Leaving the context unset and calling the rest of the chain
     * looks equivalent — an authenticated endpoint answers 401 either way — but on a
     * `PERMIT_ALL_PATTERNS` path an anonymous request **succeeds**, so bad credentials would have
     * returned 200 while the same bad token returns 401 in production: `BearerTokenAuthenticationFilter`
     * commences the entry point and aborts. A rejected attempt to authenticate is a rejected attempt on
     * every path, and this is the dev-only filter, so it says so itself instead of relying on where the
     * request was going.
     */
    private fun reject(response: HttpServletResponse, reason: String) {
        logger.warn("Refusing dev auth headers: $reason")
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid oidc_claim_* headers")
    }
}