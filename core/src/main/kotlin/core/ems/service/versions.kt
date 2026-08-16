package core.ems.service

import com.fasterxml.jackson.annotation.JsonProperty
import core.db.Executor
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.boot.restclient.RestTemplateBuilder
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.Instant

private val log = KotlinLogging.logger {}

/**
 * What is actually deployed (EZ-1709).
 *
 * Unauthenticated on purpose: the point is that whoever is reporting a bug can read the version off
 * the About page, and requiring a session excludes exactly the reporter who could not log in. The
 * repo is public and the versions are in it, so this reveals nothing that `git log` does not — but
 * it is still deliberately narrow: names and versions of executors, never their base URLs, which
 * stay behind the teacher/admin-only `/executors`.
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
    )

    data class Resp(
        @get:JsonProperty("core") val core: ComponentResp,
        @get:JsonProperty("executors") val executors: List<ExecutorResp>,
    )

    @GetMapping("/unauth/versions")
    fun controller(): Resp {
        log.debug { "Getting component versions" }
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
                    VersionsController.ExecutorResp(name, it?.version, it?.commit, it?.builtAt, reachable = it != null)
                },
                onFailure = {
                    // Info, not error: an executor being drained or restarted is ordinary, and this
                    // endpoint asking about it should not fill the log with stack traces.
                    log.info { "Executor $name did not report a version: ${it.message}" }
                    VersionsController.ExecutorResp(name, null, null, null, reachable = false)
                },
            )
        }
    }

    data class ExecutorVersionResponse(
        @get:JsonProperty("version") val version: String?,
        @get:JsonProperty("commit") val commit: String?,
        @get:JsonProperty("built_at") val builtAt: String?,
    )

    companion object {
        private const val EXECUTOR_VERSION_URL = "/v1/version"
        private val REQUEST_TIMEOUT = Duration.ofSeconds(2)
        private val CACHE_TTL = Duration.ofMinutes(5)
        private const val DEV_VERSION = "dev"
        private const val UNKNOWN = "unknown"
    }
}
