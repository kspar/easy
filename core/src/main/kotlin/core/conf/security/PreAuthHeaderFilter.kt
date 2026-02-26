package core.conf.security

import com.auth0.jwt.JWT
import core.ems.service.getOptionalHeader
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

private val log = KotlinLogging.logger {}


class PreAuthHeaderFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val oldId = request.getOptionalHeader("oidc_claim_preferred_username")
        val oldEmail = request.getOptionalHeader("oidc_claim_email")
        val oldRoles = request.getOptionalHeader("oidc_claim_easy_role")?.let {
            mapHeaderToRoles(it)
        }

        val token = request.getOptionalHeader("OIDC_access_token")
        val jwt = token?.let { JWT.decode(it) }

        val newId = jwt?.claims?.get("preferred_username")?.asString()
        val newEmail = jwt?.claims?.get("email")?.asString()
        val newGivenName = jwt?.claims?.get("given_name")?.asString()
        val newFamilyName = jwt?.claims?.get("family_name")?.asString()
        val newRoles = jwt?.claims?.get("easy_role")?.asList(String::class.java)?.let {
            mapRoleStringsToRoles(it)
        }

        if (newEmail != oldEmail ||
            newRoles != oldRoles
        ) {
            val warningMsg = """
                Old != new data for user $newId ($oldId):
                    oldEmail: '$oldEmail', newEmail: '$newEmail'
                    oldRoles: '$oldRoles', newRoles: '$newRoles'
                """
            log.warn { warningMsg }
        }

        if (oldId != null
            && newEmail != null
            && newRoles != null
        ) {
            val id = if (newId != null)
                newId
            else {
                log.warn { "newId == null, oldId: $oldId" }
                oldId
            }

            SecurityContextHolder.getContext().authentication = EasyUser(
                oldId, id, newEmail, newGivenName, newFamilyName, newRoles
            )
        }

        filterChain.doFilter(request, response)
    }
}

fun mapHeaderToRoles(rolesHeader: String): Set<EasyGrantedAuthority> =
    mapRoleStringsToRoles(rolesHeader.split(","))

fun mapRoleStringsToRoles(roleStrings: List<String>): Set<EasyGrantedAuthority> =
    roleStrings.map {
        when (it) {
            "student" -> EasyGrantedAuthority(EasyRole.STUDENT)
            "teacher" -> EasyGrantedAuthority(EasyRole.TEACHER)
            "admin" -> EasyGrantedAuthority(EasyRole.ADMIN)
            else -> throw RuntimeException("Unmapped role $it")
        }
    }.toSet()
