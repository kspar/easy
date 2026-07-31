package core.conf.security

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException

private val log = KotlinLogging.logger {}

/**
 * Builds an [EasyUser] from a Keycloak access token.
 *
 * By the time this runs, Spring Security's resource server has already verified the token's
 * signature against the IdP's JWKS and checked its issuer and expiry — so unlike the
 * `oidc_claim_*` header filter this replaced, nothing here has to trust the reverse proxy.
 * That is what allows Apache in front of core to be a plain TLS terminator, and what makes
 * `server.address: 127.0.0.1` a defence-in-depth measure rather than the only thing standing
 * between the internet and an admin session.
 *
 * A verified token missing the claims we need is rejected as an invalid token (401), not
 * treated as an anonymous request: the signature proves it came from our IdP, so absent
 * claims mean the realm's claim mappers are misconfigured and saying so is more useful than
 * a confusing 403 later on.
 */
class EasyUserJwtConverter : Converter<Jwt, AbstractAuthenticationToken> {

    override fun convert(jwt: Jwt): AbstractAuthenticationToken {
        val username = jwt.getClaimAsString("preferred_username")
        val email = jwt.getClaimAsString("email")
        // Keycloak sends easy_role as an array. getClaimAsStringList returns null for a
        // single-string claim, so fall back rather than lock out a user over realm config.
        val roleStrings = jwt.getClaimAsStringList("easy_role")
            ?: jwt.getClaimAsString("easy_role")?.let { listOf(it) }

        if (username == null || email == null || roleStrings == null) {
            val missing = buildList {
                if (username == null) add("preferred_username")
                if (email == null) add("email")
                if (roleStrings == null) add("easy_role")
            }
            log.warn { "Token for '${jwt.subject}' is missing required claims: $missing" }
            throw InvalidBearerTokenException("Token is missing required claims: $missing")
        }

        val roles = try {
            mapRoleStringsToRoles(roleStrings)
        } catch (e: RuntimeException) {
            log.warn { "Unmappable easy_role $roleStrings for '$username': ${e.message}" }
            throw InvalidBearerTokenException("Token contains an unmapped role", e)
        }

        return EasyUser(
            username,
            email,
            jwt.getClaimAsString("given_name"),
            jwt.getClaimAsString("family_name"),
            roles,
        )
    }
}
