package ee.urgas.aas.conf.security

import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import javax.servlet.FilterChain
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse


class DummyZeroAuthFilter : OncePerRequestFilter() {
    companion object {
        const val EMAIL = "ford@prefect.btl5"
        const val GIVEN_NAME = "Ford"
        const val FAMILY_NAME = "Prefect"
        val ROLES = setOf(
                EasyGrantedAuthority(EasyRole.STUDENT),
                EasyGrantedAuthority(EasyRole.TEACHER),
                EasyGrantedAuthority(EasyRole.ADMIN))
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val user = EasyUser(EMAIL, GIVEN_NAME, FAMILY_NAME, ROLES)
        SecurityContextHolder.getContext().authentication = user
        filterChain.doFilter(request, response)
    }
}
