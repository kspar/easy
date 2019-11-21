package core.conf.security

import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import javax.servlet.FilterChain
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse


class DummyZeroAuthFilter : OncePerRequestFilter() {
    companion object {
        const val USERNAME = "fp"
        const val EMAIL = "ford@prefect.btl5"
        const val GIVEN_NAME = "Ford"
        const val FAMILY_NAME = "Prefect"
        const val MOODLE_USERNAME = "ford"
        val ROLES = setOf(
                EasyGrantedAuthority(EasyRole.STUDENT),
                EasyGrantedAuthority(EasyRole.TEACHER),
                EasyGrantedAuthority(EasyRole.ADMIN))
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val user = EasyUser(USERNAME, EMAIL, GIVEN_NAME, FAMILY_NAME, ROLES, MOODLE_USERNAME)
        SecurityContextHolder.getContext().authentication = user
        filterChain.doFilter(request, response)
    }
}
