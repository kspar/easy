package core.conf.security

import core.ems.service.getOptionalHeader
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.web.filter.OncePerRequestFilter


/** Every header this filter reads. Named once so the presence check cannot drift from the reads. */
private val CLAIM_HEADERS = listOf(
    "oidc_claim_preferred_username",
    "oidc_claim_email",
    "oidc_claim_given_name",
    "oidc_claim_family_name",
    "oidc_claim_easy_role",
)

/**
 * Trusts `oidc_claim_*` request headers verbatim, so anything that can reach core can be any
 * user. Installed only when `easy.core.auth-enabled` is false — local dev and curl-based API
 * testing (doc/core/api-testing.md). Production verifies JWTs instead; see [EasyUserJwtConverter].
 *
 * **Keep these header values ASCII, and not because you get told off if you do not.** Request headers
 * are decoded as ISO-8859-1, so a UTF-8 name arrives as mojibake — and whether that is *refused* comes
 * down to which byte it happens to land on. `StrictHttpFirewall` rejects a control character, and the
 * second UTF-8 byte of the Estonian **uppercase** vowels falls in the C1 range: `Ü` is `0xC3 0x9C`, so
 * it arrives as `Ã` + U+009C and the request is refused with a 400 that says nothing about names.
 *
 * The **lowercase** ones are not: `ä` is `0xC3 0xA4` → `Ã¤`, and `ö`, `ü`, `õ` likewise land on
 * assigned, non-control characters. Those are accepted and stored mangled, silently. So the rule is
 * not "you will be stopped", it is "half of these are corrupted without complaint" — which is the
 * worse half and the actual reason to keep them ASCII.
 *
 * Nothing consumes these two names today, which is the only reason the blast radius is nil:
 * `account_checkin` takes the display name from its request body, not from the principal. That is
 * load-bearing and worth knowing before anything starts reading `EasyUser.givenName`.
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
        //
        // Presence is asked of the **raw** headers, not of the values above, and the difference is not
        // hypothetical. `getOptionalHeader` nulls a blank value, so testing those would classify a
        // request that sent every claim header empty as anonymous — credentials offered, unusable, and
        // answered 200 on a `permitAll` path. `EMS.postman_collection.json` sends these as `{{…}}`
        // variables, so running it against an unset environment does exactly that. All five names, so
        // a request carrying only `given_name` is an attempt too.
        val attempted = CLAIM_HEADERS.any { request.getHeader(it) != null }
        if (!attempted) {
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