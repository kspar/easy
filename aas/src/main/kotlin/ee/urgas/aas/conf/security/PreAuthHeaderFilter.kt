package ee.urgas.aas.conf.security

import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import javax.servlet.FilterChain
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse


class PreAuthHeaderFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val email = getOptionalHeader("oidc_claim_email", request)
        val givenName = getOptionalHeader("oidc_claim_given_name", request)
        val familyName = getOptionalHeader("oidc_claim_family_name", request)
        val roles = getOptionalHeader("oidc_claim_easy_role", request)

        if (email != null
                && givenName != null
                && familyName != null
                && roles != null) {

            val user = EasyUser(email, givenName, familyName, mapHeaderToRoles(roles))
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
