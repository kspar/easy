package core.conf.security

import core.testing.Auth
import core.testing.Endpoint
import core.testing.EndpointInventory
import core.testing.EndpointSamples
import core.testing.IntegrationTest
import core.testing.Sample
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.context.TestSecurityContextHolder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

/**
 * **No endpoint is reachable by a role that should not reach it.**
 *
 * That sentence is the point, and it is a different claim from "we tested some endpoints". It holds
 * over all 124 of them at once, and it keeps holding when somebody adds the 125th, because an
 * endpoint with no callable sample fails the build rather than going unchecked.
 *
 * ### Why this is affordable
 *
 * `@Secured` is evaluated **before the handler body runs**. So a negative check needs no fixture at
 * all: call with the wrong role and an id that points at nothing, and the answer is 403 regardless
 * of what is in the database. Building a realistic scenario per endpoint would cost weeks and prove
 * less, because it would prove it about one row rather than about the endpoint.
 *
 * ### What it asserts
 *
 * | caller | expectation |
 * | --- | --- |
 * | anonymous | **401**, unless the endpoint is in `PERMIT_ALL_PATTERNS` |
 * | a role not in `@Secured` | **exactly 403** |
 *
 * Asserting *exactly* 403 rather than "not 200" is deliberate. "Not 200" passes on a 500, on a 404,
 * and on the 400 you get when a request never reached the handler — three ways to be told nothing
 * while looking green.
 *
 * A third leg — call with an *allowed* role and assert the caller is not refused — was written and
 * abandoned. It is the obvious counterpart and it does not work here; the reason is specific to this
 * codebase and is written up on `@Secured matches the URL the endpoint is published under`, which
 * replaced it. Read that before adding it back.
 *
 * ### 400 is inconclusive, and is reported as such
 *
 * Spring resolves and validates `@RequestBody` arguments before invoking the method, so a body that
 * Jackson cannot read or `@Valid` rejects produces 400 *before the role is considered*. Such a
 * result does not mean the endpoint refused the caller — it means the question was never asked.
 * These fail with an instruction to fix the body rather than being counted as passes.
 */
@IntegrationTest
class EndpointAuthorizationMatrixTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val mapping: RequestMappingHandlerMapping,
) {

    private val endpoints: List<Endpoint> by lazy { EndpointInventory.all(mapping) }
    private fun samples(): List<Sample> = endpoints.mapNotNull { EndpointSamples.sampleFor(it) }

    private fun call(sample: Sample, caller: RequestPostProcessor?): Int {
        val method = if (sample.endpoint.method == EndpointInventory.ANY_METHOD) "GET" else sample.endpoint.method

        val request = if (sample.multipartFileParam != null) {
            MockMvcRequestBuilders.multipart(HttpMethod.valueOf(method), sample.path)
                .file(MockMultipartFile(sample.multipartFileParam, "x.png", "image/png", byteArrayOf(1)))
        } else {
            MockMvcRequestBuilders.request(HttpMethod.valueOf(method), sample.path)
                .also { req -> sample.body?.let { req.contentType(MediaType.APPLICATION_JSON).content(it) } }
        }

        // Clearing rather than merely omitting the post-processor. `authentication()` writes into
        // the static TestSecurityContextHolder, which JUnit clears per *test method*, not per
        // request — so an anonymous call made after an authenticated one in the same method would
        // silently inherit the previous caller and pass its 401 assertion for entirely the wrong
        // reason. No method mixes them today; this makes it safe for the one that eventually does.
        if (caller == null) TestSecurityContextHolder.clearContext() else request.with(caller)
        return mockMvc.perform(request).andReturn().response.status
    }

    /**
     * The mechanism that makes coverage complete by construction.
     *
     * Adding a controller breaks this until it is either callable or deliberately excluded. Without
     * it the matrix would quietly shrink to whatever happened to work.
     */
    @Test
    fun `every endpoint has a callable sample or a justified exclusion`() {
        val uncovered = endpoints
            .filter { EndpointSamples.sampleFor(it) == null }
            .map { EndpointSamples.key(it) }
            .filterNot { it in EndpointSamples.excluded }
            .distinct()
            .sorted()

        assertTrue(uncovered.isEmpty()) {
            "These endpoints have no callable sample, so the matrix below never asks about them:\n" +
                    uncovered.joinToString("\n") { "  $it" } +
                    "\n\nAlmost always this is a `@RequestBody` whose type JsonBodies cannot render — " +
                    "a field type it does not know. Teach it that type if the type is general, or add " +
                    "an entry to EndpointSamples.bodyOverrides if the shape is peculiar to this " +
                    "endpoint. Excluding it is the last resort, and needs a reason."
        }

        val stale = (EndpointSamples.excluded.keys - endpoints.map { EndpointSamples.key(it) }.toSet()).sorted()
        assertTrue(stale.isEmpty()) {
            "These exclusions name endpoints that do not exist:\n" +
                    stale.joinToString("\n") { "  $it" } +
                    "\n\nDelete them — an exclusion that names nothing is one nobody will re-examine."
        }

        val unjustified = EndpointSamples.excluded.filterValues { it.isBlank() }.keys
        assertTrue(unjustified.isEmpty()) {
            "These exclusions have no reason:\n" + unjustified.joinToString("\n") { "  $it" }
        }
    }

    @Test
    fun `anonymous callers are rejected everywhere except the public endpoints`() {
        val reachable = samples()
            .filterNot { isPermitAllPath(it.endpoint.pattern) }
            .mapNotNull { sample ->
                val status = call(sample, caller = null)
                if (status == 401) null else "${EndpointSamples.key(sample.endpoint)} -> $status"
            }
            .sorted()

        assertTrue(reachable.isEmpty()) {
            "These endpoints answered an UNAUTHENTICATED request with something other than 401:\n" +
                    reachable.joinToString("\n") { "  $it" } +
                    "\n\nAnything reachable without a token is reachable by the internet. If one of " +
                    "these is meant to be, it belongs in PERMIT_ALL_PATTERNS in SecurityConf.kt — and " +
                    "read EndpointSecuritySurfaceTest first, which asserts that list in both directions."
        }
    }

    @Test
    fun `a role outside @Secured is refused with 403`() {
        val problems = mutableListOf<String>()
        val inconclusive = mutableListOf<String>()

        for (sample in samples()) {
            val allowed = sample.endpoint.securedRoles.toSet()
            if (allowed.isEmpty()) continue // covered by EndpointSecuritySurfaceTest

            for ((label, caller, role) in callers()) {
                if (role in allowed) continue
                val status = call(sample, caller)
                when (status) {
                    403 -> {}
                    400 -> inconclusive += "${EndpointSamples.key(sample.endpoint)} as $label -> 400"
                    else -> problems += "${EndpointSamples.key(sample.endpoint)} as $label -> $status (expected 403)"
                }
            }
        }

        // Both lists are reported in one failure, and the authorization problems come first.
        // Asserting them separately buried a real privilege escalation behind an unrelated
        // body-generation problem: the build failed on the 400s, and nobody saw the 200 until
        // somebody fixed the body. The less important finding must not be able to hide the
        // more important one.
        assertTrue(problems.isEmpty() && inconclusive.isEmpty()) {
            buildString {
                if (problems.isNotEmpty()) {
                    append("These endpoints did not refuse a caller whose role is not in their @Secured list:\n")
                    append(problems.joinToString("\n") { "  $it" })
                    append(
                        "\n\nA 401 here usually means the caller was not authenticated at all; anything " +
                                "in the 2xx range means the endpoint ran for someone it should have turned away.\n\n"
                    )
                }
                if (inconclusive.isNotEmpty()) {
                    append("These calls returned 400, so the role check was never reached — Spring rejects ")
                    append("an unreadable or invalid @RequestBody before invoking the method, and therefore ")
                    append("before @Secured:\n")
                    append(inconclusive.joinToString("\n") { "  $it" })
                    append(
                        "\n\nThis is NOT a pass — the question was never asked. Give the endpoint a working " +
                                "body: teach JsonBodies the constraint if it is general, or add an entry to " +
                                "EndpointSamples.bodyOverrides if it is peculiar to this endpoint."
                    )
                }
            }
        }
    }

    /**
     * `@Secured` agrees with the URL the endpoint is published under.
     *
     * ### Why this, and not "the right role gets in"
     *
     * The obvious third leg — call with an allowed role and assert it is *not* refused — was written
     * first and had to be abandoned, because its premise is wrong for this application. It failed on
     * **111 of 124 endpoints**, every one of them correctly.
     *
     * The reason is worth knowing before writing any authorization test here: **`@Secured` is only
     * the coarse first gate. The real authorization is at the data layer** — `assertAccess {
     * teacherOnCourse(courseId) }` and its siblings in `core/ems/service/access_control/`. A teacher
     * who genuinely holds `ROLE_TEACHER` is still, correctly, refused course 999999, because they
     * have no access to it. At the HTTP level that 403 is indistinguishable from an `@Secured`
     * refusal, so a fixture-free positive leg cannot tell "the annotation let me in" from "the
     * annotation kept me out".
     *
     * Proving the positive direction therefore needs real fixtures, per endpoint — which is
     * behavioural coverage of access control, and belongs with that work rather than here.
     *
     * What *is* checkable without fixtures is that the annotation matches the URL. These paths are a
     * deliberate convention, and a mismatch is a real and quiet mistake: an endpoint published under
     * `/admin/` but annotated for teachers reads as correct in review, because the annotation is
     * present and names a real role.
     */
    @Test
    fun `@Secured matches the URL the endpoint is published under`() {
        val student = EasyRole.STUDENT.roleWithPrefix
        val teacher = EasyRole.TEACHER.roleWithPrefix
        val admin = EasyRole.ADMIN.roleWithPrefix

        // Measured, not guessed: today every endpoint under these prefixes carries exactly the set
        // below, with no exceptions at all. Asserting *equality* rather than membership is what
        // makes this catch a widening — adding a role to an endpoint is a privilege change, and
        // "roles must include X" would wave it through. There is no escape hatch here on purpose;
        // if a genuine exception appears, adding one is the moment to think about it.
        val required = mapOf(
            "/v2/admin/" to setOf(admin),
            "/v2/student/" to setOf(student),
            "/v2/teacher/" to setOf(admin, teacher),
        )

        val mismatched = endpoints.mapNotNull { e ->
            val roles = e.securedRoles.toSet()
            if (roles.isEmpty()) return@mapNotNull null

            // A genuine prefix, not a substring anywhere in the path. `in` matched
            // `/v2/courses/teacher/{courseId}/grades` as a teacher-prefix endpoint — harmless there
            // by luck, and wrong in general: an endpoint at `/v2/exercises/{id}/student/progress`
            // would be held to the student role set and told it was "published under /student/",
            // which is not a description of its URL.
            val prefix = required.keys.firstOrNull { e.pattern.startsWith(it) } ?: return@mapNotNull null
            val expected = required.getValue(prefix)
            if (roles == expected) return@mapNotNull null

            val direction = when {
                roles.containsAll(expected) -> "wider than"
                expected.containsAll(roles) -> "narrower than"
                else -> "different from"
            }
            "${EndpointSamples.key(e)} — under $prefix, @Secured is $roles, $direction the $expected " +
                    "every other endpoint there carries"
        }.sorted()

        assertTrue(mismatched.isEmpty()) {
            "These endpoints are annotated inconsistently with the path they are published under:\n" +
                    mismatched.joinToString("\n") { "  $it" } +
                    "\n\nThe URL is a promise about who the endpoint is for, and the two disagreeing " +
                    "reads as correct in review, because the annotation is present and names real " +
                    "roles. A **wider** set is a privilege widening and deserves the most scrutiny. " +
                    "Fix whichever side is wrong — and prefer fixing the annotation, since moving an " +
                    "endpoint to a different path is a breaking API change."
        }
    }

    /** The three single-role callers, each holding exactly one role. */
    private fun callers(): List<Triple<String, RequestPostProcessor, String>> = listOf(
        Triple("student", Auth.asStudent(), EasyRole.STUDENT.roleWithPrefix),
        Triple("teacher", Auth.asTeacher(), EasyRole.TEACHER.roleWithPrefix),
        Triple("admin", Auth.asAdmin(), EasyRole.ADMIN.roleWithPrefix),
    )
}
