package core.conf.security

import core.testing.Endpoint
import core.testing.EndpointInventory
import core.testing.IntegrationTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.server.PathContainer
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import org.springframework.web.util.pattern.PathPatternParser

/**
 * Who can reach what, asserted over every endpoint at once.
 *
 * The point is **coverage by construction**. "Write a test per controller" leaves a controller
 * added next month untested by default, and with 117 of them that never converges. These guards
 * instead fail the build until somebody *decides* which category the new endpoint is in — the same
 * shape as [core.ems.cron.RichTextColumnsTest], which is where the idea in this repo comes from.
 *
 * The failure being prevented is specific and silent. An endpoint with no `@Secured` does not
 * become unreachable; it falls through to `anyRequest().authenticated()`, which means **any
 * logged-in user**, students included. On an admin endpoint that is a privilege escalation that
 * nothing else in the codebase would notice, because it looks exactly like working code.
 */
@IntegrationTest
class EndpointSecuritySurfaceTest(@Autowired private val mapping: RequestMappingHandlerMapping) {

    private val endpoints: List<Endpoint> by lazy { EndpointInventory.all(mapping) }
    private val parser = PathPatternParser()

    private fun isPermitAll(e: Endpoint) = PERMIT_ALL_PATTERNS.any { pattern ->
        parser.parse(pattern).matches(PathContainer.parsePath(e.pattern))
    }

    /**
     * Endpoints that are deliberately reachable by any authenticated user, with no `@Secured`.
     *
     * Keyed by `METHOD /pattern` so that changing the path or the verb of one re-opens the
     * decision. Every entry needs a reason: this list is the escape hatch, and an escape hatch
     * nobody has to justify is just a hole.
     */
    private val authenticatedButUnrestricted = mapOf(
        // Spring Boot's own BasicErrorController, not ours. It is the target of the servlet
        // container's ERROR dispatch — the thing that renders whatever status a failed request
        // already has — so it neither reads our data nor takes a caller. Annotating it is not
        // possible without replacing it, and securing it would only mean a failed request gets a
        // different failure.
        // ANY, not GET: these mappings declare no HTTP method, so they answer every verb, and the
        // key says so rather than quietly exempting the rest.
        "ANY /error" to "Spring Boot's BasicErrorController: the ERROR dispatch target, holds no data of ours",
    )

    @Test
    fun `every endpoint is @Secured, permitAll, or an explicitly justified exception`() {
        // Census printed from inside a test that can fail, rather than from a @Test of its own.
        // A test that only prints cannot fail, and doc/testing.md's own rule is that a test which
        // cannot fail is worse than no test because it still gets counted.
        val census = endpoints.groupingBy {
            when {
                it.securedRoles.isNotEmpty() -> it.securedRoles.sorted().joinToString(",")
                isPermitAll(it) -> "<public>"
                else -> "<any authenticated>"
            }
        }.eachCount()
        println("Endpoint security surface (${endpoints.size} endpoints):")
        census.entries.sortedByDescending { it.value }.forEach { (roles, count) ->
            println("  %4d  %s".format(count, roles))
        }

        val unaccounted = endpoints
            .filter { it.securedRoles.isEmpty() }
            .filterNot { isPermitAll(it) }
            .filterNot { "${it.method} ${it.pattern}" in authenticatedButUnrestricted }
            .map { it.toString() }
            .sorted()

        assertTrue(unaccounted.isEmpty()) {
            "These endpoints have no @Secured annotation and are not in SecurityConf's permitAll list:\n" +
                    unaccounted.joinToString("\n") { "  $it" } +
                    "\n\nThey are therefore reachable by ANY authenticated user, including a student — " +
                    "Spring falls through to anyRequest().authenticated(). Add @Secured with the roles " +
                    "that should reach it; or, if it is genuinely public, add the path to " +
                    "PERMIT_ALL_PATTERNS in SecurityConf.kt; or, if any signed-in user really may call " +
                    "it, add it to authenticatedButUnrestricted in this test with the reason."
        }
    }

    /**
     * The escape hatch cannot rot.
     *
     * Same rule as the exception lists in [core.db.SchemaMatchesTablesTest]: an entry that no longer
     * describes anything must be deleted, and an entry with no reason is rejected. Without the first
     * half, an endpoint that later gains `@Secured` keeps a stale exemption that would silently
     * re-open if the annotation were ever removed again.
     */
    @Test
    fun `the unrestricted-endpoint exceptions are still real, and still justified`() {
        val actuallyUnrestricted = endpoints
            .filter { it.securedRoles.isEmpty() }
            .filterNot { isPermitAll(it) }
            .map { "${it.method} ${it.pattern}" }
            .toSet()

        val stale = (authenticatedButUnrestricted.keys - actuallyUnrestricted).sorted()
        assertTrue(stale.isEmpty()) {
            "These authenticatedButUnrestricted entries no longer describe an unrestricted endpoint:\n" +
                    stale.joinToString("\n") { "  $it" } +
                    "\n\nThe endpoint gained @Secured, became public, or was removed. Delete the entry — " +
                    "a stale exemption would silently excuse the endpoint again if it regressed."
        }

        val unjustified = authenticatedButUnrestricted.filterValues { it.isBlank() }.keys.sorted()
        assertTrue(unjustified.isEmpty()) {
            "These authenticatedButUnrestricted entries have no reason:\n" +
                    unjustified.joinToString("\n") { "  $it" }
        }
    }

    @Test
    fun `every permitAll pattern matches at least one real endpoint`() {
        val orphans = PERMIT_ALL_PATTERNS.filter { pattern ->
            val parsed = parser.parse(pattern)
            endpoints.none {
                parsed.matches(org.springframework.http.server.PathContainer.parsePath(it.pattern))
            }
        }

        assertTrue(orphans.isEmpty()) {
            "These PERMIT_ALL_PATTERNS in SecurityConf.kt match no endpoint:\n" +
                    orphans.joinToString("\n") { "  $it" } +
                    "\n\nEither the endpoint was renamed or removed and the pattern outlived it — in " +
                    "which case delete the pattern, because a public path with nothing behind it is " +
                    "a hole waiting for something to be put at that address — or the pattern is " +
                    "simply wrong and the endpoint it was meant for is currently NOT public."
        }
    }

    /**
     * The direction that actually catches something being opened to the internet.
     *
     * A pattern broader than the endpoints it was written for does not fail anything, look wrong,
     * or produce a log line. The articles pattern matching one more handler than intended is
     * invisible until someone reads the annotations. The comments in `SecurityConf` explain at
     * length why those patterns end in a single-segment wildcard rather than a recursive one; this
     * is that reasoning made executable.
     */
    @Test
    fun `no permitAll pattern reaches an endpoint outside the intended public set`() {
        val intendedlyPublic = setOf(
            "POST /v2/unauth/exercises/{exerciseId}/anonymous/autoassess",
            "GET /v2/unauth/exercises/{exerciseId}/anonymous/details",
            "GET /v2/unauth/versions",
            "GET /v2/unauth/articles/{articleId}",
            "GET /v2/resource/{key}/{filename}",
        )

        val unexpectedlyPublic = endpoints
            .filter { isPermitAll(it) }
            .map { "${it.method} ${it.pattern}" }
            .distinct()
            .filterNot { it in intendedlyPublic }
            .sorted()

        assertTrue(unexpectedlyPublic.isEmpty()) {
            "These endpoints are reachable with NO AUTHENTICATION AT ALL, by anyone on the internet, " +
                    "and are not in this test's intended-public list:\n" +
                    unexpectedlyPublic.joinToString("\n") { "  $it" } +
                    "\n\nEither a PERMIT_ALL_PATTERN in SecurityConf.kt is broader than it should be — " +
                    "check whether it needs to be narrower rather than adding the endpoint here — or " +
                    "this endpoint really is meant to be public, in which case add it to the list in " +
                    "this test and be sure its handler takes no caller and leaks nothing."
        }

        // And the reverse, so the list cannot rot into naming things that no longer exist.
        val stale = intendedlyPublic - endpoints.filter { isPermitAll(it) }.map { "${it.method} ${it.pattern}" }.toSet()
        assertTrue(stale.isEmpty()) {
            "This test's intended-public list names endpoints that are not actually public:\n" +
                    stale.joinToString("\n") { "  $it" } +
                    "\n\nThey were renamed, removed, or are no longer matched by any permitAll pattern."
        }
    }

    @Test
    fun `@Secured names only real roles`() {
        val valid = EasyRole.entries.map { it.roleWithPrefix }.toSet()

        val bad = endpoints
            .flatMap { e -> e.securedRoles.filterNot { it in valid }.map { "$e  ->  $it" } }
            .distinct()
            .sorted()

        assertTrue(bad.isEmpty()) {
            "These @Secured annotations name roles that do not exist in EasyRole:\n" +
                    bad.joinToString("\n") { "  $it" } +
                    "\n\nValid values are $valid. A typo here does not fail loudly — it produces an " +
                    "authority nobody is ever granted, so the endpoint becomes unreachable rather " +
                    "than insecure. Quieter than the alternative, still wrong."
        }
    }

}
