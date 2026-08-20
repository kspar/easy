package core.ems.service

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Executor
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.boot.restclient.RestTemplateBuilder
import org.springframework.security.access.annotation.Secured
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.Instant

private val log = KotlinLogging.logger {}

/**
 * What is actually deployed (EZ-1709), and which grading libraries each executor has (EZ-1781).
 *
 * **Teacher and admin only, since EZ-1782.** It was unauthenticated for its first year, on the
 * argument that a bug reporter who could not log in is exactly the reporter whose version matters
 * most. kspar decided against publishing the deployment's component versions to the internet, so the
 * cost of that decision is a bug report from a signed-out user that no longer carries a version, and
 * the About page tells such a viewer to sign in rather than silently showing them less.
 *
 * It moved off `/unauth/` at the same time: leaving it there would have made the path say the
 * opposite of what the annotation does, and the patterns in `PERMIT_ALL_PATTERNS` match on that
 * `unauth` segment, so it is load-bearing rather than decorative.
 *
 * Still deliberately narrow: names and versions of executors, never their base URLs, which stay
 * behind `/executors`. Narrowing the audience does not make the payload's own limits less worth
 * keeping — a teacher has no more business knowing an executor's internal address than anyone else.
 *
 * Web reports its own version without asking anyone: it is baked into the bundle at build time.
 */
@RestController
@RequestMapping("/v2")
class VersionsController(private val versionsService: VersionsService) {

    data class ComponentResp(
        @get:JsonProperty("version") val version: String,
        @get:JsonProperty("commit") val commit: String,
        @get:JsonProperty("built_at") val builtAt: String?,
    )

    /**
     * One grading library inside one image: what the pins asked for, and what is actually there.
     *
     * Both, rather than one resolved answer, because they are different questions and the difference
     * is the interesting part. `declared` is intent; `installed` is what pip put in the image. For
     * anything CI built they agree by construction — the image's own smoke check refuses to publish
     * otherwise — so a disagreement means somebody's belief about a host is wrong, which is exactly
     * the state that went unnoticed for a fortnight in August 2026 (EZ-1781).
     *
     * Either may be null. An unpinned library has nothing declared; one whose version cannot be read
     * out of an unlabelled image has nothing installed.
     */
    data class GradingLibraryResp(
        @get:JsonProperty("name") val name: String,
        @get:JsonProperty("declared") val declared: String?,
        @get:JsonProperty("installed") val installed: String?,
    )

    /**
     * A grading image on one executor.
     *
     * `source` says how the versions were established — `label` for anything CI built and stamped,
     * `pip` for an image inspected by running pip inside it, `unknown` when neither worked. Carried
     * for diagnostics rather than for display: the About page does not render it, because on a quiet
     * list of versions a provenance badge is noise, and the number is either right or visibly
     * disagreeing with itself either way.
     */
    data class GradingImageResp(
        @get:JsonProperty("name") val name: String,
        @get:JsonProperty("created_at") val createdAt: String?,
        @get:JsonProperty("source") val source: String?,
        @get:JsonProperty("libraries") val libraries: List<GradingLibraryResp>,
    )

    data class ExecutorResp(
        @get:JsonProperty("name") val name: String,
        @get:JsonProperty("version") val version: String?,
        @get:JsonProperty("commit") val commit: String?,
        // When the executor's code was put on its host. aae has no build step, so this is the
        // mtime of its source rather than a build time — the same question, answered the only way
        // a copied-not-compiled component can answer it.
        @get:JsonProperty("built_at") val builtAt: String?,
        // False when the executor did not answer in time. Rendering "unreachable" is more useful
        // than omitting the row: an executor that is registered but silent is worth seeing.
        @get:JsonProperty("reachable") val reachable: Boolean,
        /**
         * Empty for an executor that did not answer, for one running a version of aae that predates
         * this, and for one whose Docker daemon is down. Three states that are all honestly "we
         * cannot say", and which the About page renders identically — because to a reader they are
         * the same statement, and a public field nobody branches on will be wrong within a year.
         */
        @get:JsonProperty("grading_images") val gradingImages: List<GradingImageResp>,
    )

    data class Resp(
        @get:JsonProperty("core") val core: ComponentResp,
        @get:JsonProperty("executors") val executors: List<ExecutorResp>,
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/versions")
    fun controller(caller: EasyUser): Resp {
        log.debug { "${caller.id} is getting component versions" }
        return Resp(versionsService.core(), versionsService.executors())
    }
}

@Service
class VersionsService(buildPropertiesProvider: ObjectProvider<BuildProperties>) {

    /**
     * Absent when the jar was built without `bootBuildInfo` having run — which is normal for a
     * `gradlew bootRun` in some IDE configurations. Reporting "dev" beats refusing to start, since
     * this endpoint is diagnostic and nothing depends on it.
     */
    private val build: BuildProperties? = buildPropertiesProvider.ifAvailable

    fun core() = VersionsController.ComponentResp(
        version = build?.version ?: DEV_VERSION,
        commit = build?.get("commit") ?: UNKNOWN,
        builtAt = build?.time?.toString(),
    )

    /**
     * Executor versions, cached.
     *
     * Cached because this endpoint is public and unauthenticated, so without it a page refresh in a
     * loop turns into HTTP traffic against every executor. [CACHE_TTL] is short enough that a
     * redeployed executor shows its new version within minutes, and long enough that this is never
     * the reason an executor is busy.
     */
    @Synchronized
    fun executors(): List<VersionsController.ExecutorResp> {
        val cached = cache
        if (cached != null && Duration.between(cached.first, Instant.now()) < CACHE_TTL) {
            return cached.second
        }
        val fresh = queryExecutors()
        cache = Instant.now() to fresh
        return fresh
    }

    private var cache: Pair<Instant, List<VersionsController.ExecutorResp>>? = null

    /**
     * Forget the cached snapshot.
     *
     * This cache is hand-rolled rather than `@Cacheable` because it needs a TTL and the
     * `ConcurrentMapCacheManager` this application uses has none — which also means
     * `CachingService.invalidateAll` does not reach it, and neither does anything else. Nothing in
     * production wants that: a five-minute stale executor version is the point.
     *
     * A test does. One Spring context serves the whole suite, so without this the first test to ask
     * for versions decides what every later one sees, for five minutes — and since test fixtures
     * tend to be near-identical, the later assertions pass while examining the earlier test's data.
     */
    @Synchronized
    fun clearCache() {
        cache = null
    }

    private fun queryExecutors(): List<VersionsController.ExecutorResp> {
        val executors = transaction {
            Executor.selectAll().sortedBy { it[Executor.name] }
                .map { it[Executor.name] to it[Executor.baseUrl] }
        }

        return executors.map { (name, baseUrl) ->
            // Timeouts in seconds, not the hour-long one grading uses: this is a page render, and
            // an executor that is down must cost a couple of seconds rather than hanging the
            // request until someone gives up.
            val client = RestTemplateBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .readTimeout(REQUEST_TIMEOUT)
                .build()

            runCatching {
                client.getForObject(baseUrl + EXECUTOR_VERSION_URL, ExecutorVersionResponse::class.java)
            }.fold(
                onSuccess = {
                    VersionsController.ExecutorResp(
                        name, it?.version, it?.commit, it?.builtAt,
                        reachable = it != null,
                        // Straight through, with no interpretation. Core deliberately knows nothing
                        // about grading libraries — it does not decide which images exist, cannot
                        // reach a Docker daemon, and has no business acquiring an opinion about
                        // either. The executor is the only thing that can answer, so this is a pipe.
                        gradingImages = it?.gradingImages.orEmpty(),
                    )
                },
                onFailure = {
                    // Info, not error: an executor being drained or restarted is ordinary, and this
                    // endpoint asking about it should not fill the log with stack traces.
                    log.info { "Executor $name did not report a version: ${it.message}" }
                    VersionsController.ExecutorResp(
                        name, null, null, null, reachable = false, gradingImages = emptyList()
                    )
                },
            )
        }
    }

    data class ExecutorVersionResponse(
        @get:JsonProperty("version") val version: String?,
        @get:JsonProperty("commit") val commit: String?,
        @get:JsonProperty("built_at") val builtAt: String?,
        /**
         * Nullable.
         *
         * `application.yaml` sets `FAIL_ON_NULL_FOR_PRIMITIVES: true`, so a non-nullable field here
         * would make an executor that predates EZ-1781 — one that simply omits the key — fail to
         * deserialise, and core would report a perfectly healthy executor as unreachable. The
         * opposite direction is already safe: `FAIL_ON_UNKNOWN_PROPERTIES` is off, so an old core
         * reading a new executor ignores what it does not know. Deploy order does not matter in
         * either direction, which is the point.
         */
        @get:JsonProperty("grading_images") val gradingImages: List<VersionsController.GradingImageResp>?,
    )

    companion object {
        private const val EXECUTOR_VERSION_URL = "/v1/version"
        private val REQUEST_TIMEOUT = Duration.ofSeconds(2)
        private val CACHE_TTL = Duration.ofMinutes(5)
        private const val DEV_VERSION = "dev"
        private const val UNKNOWN = "unknown"
    }
}
