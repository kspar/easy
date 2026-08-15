package core.testing

import org.springframework.security.access.annotation.Secured
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.mvc.method.RequestMappingInfo
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

/**
 * One HTTP endpoint, as Spring actually resolved it.
 *
 * [pattern] is the full resolved path (`/v2/courses/{courseId}/exercises`), not the fragment
 * written on the method — the class-level `@RequestMapping("/v2")` is already folded in.
 */
data class Endpoint(
    val method: String,
    val pattern: String,
    val handler: HandlerMethod,
) {
    /** Roles from `@Secured`, method-level first, falling back to the class. Empty means none. */
    val securedRoles: List<String> by lazy {
        (handler.getMethodAnnotation(Secured::class.java)
            ?: handler.beanType.getAnnotation(Secured::class.java))
            ?.value?.toList().orEmpty()
    }

    val controller: String get() = handler.beanType.simpleName
    override fun toString() = "$method $pattern  (${controller}#${handler.method.name})"
}

/**
 * Every endpoint the running application exposes.
 *
 * Read from the live `RequestMappingHandlerMapping` rather than by scanning the classpath for
 * `@RestController`. That matters: the handler mapping holds the *resolved* patterns and HTTP
 * methods after Spring has combined class- and method-level annotations, which is what a caller
 * actually reaches. A classpath scan would be re-implementing that combination, and the guards
 * built on this exist precisely because re-implementing things by hand is how they drift.
 *
 * Requires a Spring context, so callers are `@IntegrationTest`. Cheap regardless — the context is
 * shared across the whole suite.
 */
object EndpointInventory {

    /** The [Endpoint.method] of a mapping that declares no HTTP method, and so answers all of them. */
    const val ANY_METHOD = "ANY"

    fun all(mapping: RequestMappingHandlerMapping): List<Endpoint> {
        val endpoints = mapping.handlerMethods.flatMap { (info, handler) ->
            patternsOf(info).flatMap { pattern ->
                // A mapping with no declared method answers *every* verb, and it is recorded as
                // ANY rather than as GET.
                //
                // This is load-bearing, because the exemption lists downstream are keyed
                // "METHOD /pattern". Recording such a mapping as GET would mean somebody writes
                // `@RequestMapping("/v2/admin/thing")` with no method, records "GET /v2/admin/thing"
                // as a reviewed exception, and thereby exempts its POST, PUT and DELETE too — while
                // the diff being reviewed says GET. ANY forces the exemption to admit what it covers.
                val methods = info.methodsCondition.methods.map { it.name }.ifEmpty { listOf(ANY_METHOD) }
                methods.map { Endpoint(it, pattern, handler) }
            }
        }

        // A scan that finds nothing looks exactly like a clean run, and every guard in this file's
        // orbit is built on the result. Same assertion ExposedTables carries, same reason.
        check(endpoints.size >= 100) {
            "Only ${endpoints.size} endpoints found — the handler mapping was not read correctly, " +
                    "which would make every test built on this inventory pass vacuously."
        }
        return endpoints
    }

    /**
     * The path patterns of a mapping, whichever matcher Spring is configured with.
     *
     * Boot 4 defaults to `PathPatternParser`, so `pathPatternsCondition` is the populated one and
     * `patternsCondition` throws rather than returning empty when it is not in use — hence the
     * order and the fallback rather than a union.
     */
    private fun patternsOf(info: RequestMappingInfo): Set<String> =
        info.pathPatternsCondition?.patternValues
            ?: info.patternsCondition?.patterns
            ?: emptySet()
}
