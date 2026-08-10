package core.ems.service

import com.fasterxml.jackson.annotation.JsonProperty
import com.zaxxer.hikari.HikariDataSource
import core.aas.AutoGradeScheduler
import core.conf.security.EasyUser
import core.db.Executor
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.io.File
import java.lang.management.ManagementFactory
import javax.sql.DataSource

private val log = KotlinLogging.logger {}

/**
 * How this deployment is doing, for an admin (EZ-1709).
 *
 * A curated endpoint rather than Spring Actuator, deliberately. Actuator is a broad and sensitive
 * surface — one `exposure.include: "*"` in an environment's application.yaml turns it into `/env`
 * and `/heapdump` — and it would need its own security rule and its own CORS entry, since
 * everything else here lives under `/v2`. This exposes exactly what someone asks when a deployment
 * is behaving oddly, under the same `@Secured` model as every other endpoint.
 *
 * It also reports two things Actuator could not know: how deep the grading queues are, and which
 * Liquibase changeset the schema is actually on — the latter being the thing to look at when a
 * deploy half-applies, which is exactly when nobody can tell what is wrong.
 */
@RestController
@RequestMapping("/v2")
class OperatingInfoController(private val operatingInfoService: OperatingInfoService) {

    data class JvmResp(
        @get:JsonProperty("started_at") val startedAt: String,
        @get:JsonProperty("uptime_sec") val uptimeSec: Long,
        @get:JsonProperty("heap_used_mb") val heapUsedMb: Long,
        // -1 when the JVM reports no maximum, which is legal and means "unbounded".
        @get:JsonProperty("heap_max_mb") val heapMaxMb: Long,
        @get:JsonProperty("threads") val threads: Int,
        @get:JsonProperty("java_version") val javaVersion: String,
    )

    data class DbPoolResp(
        @get:JsonProperty("active") val active: Int,
        @get:JsonProperty("idle") val idle: Int,
        // Non-zero here means requests are queueing for a connection, which is the shape of a
        // pool-exhaustion incident long before anything starts failing.
        @get:JsonProperty("waiting") val waiting: Int,
        @get:JsonProperty("max") val max: Int,
    )

    data class SchemaResp(
        @get:JsonProperty("changeset") val changeset: String?,
        @get:JsonProperty("filename") val filename: String?,
        @get:JsonProperty("applied_at") val appliedAt: String?,
        @get:JsonProperty("total_changesets") val totalChangesets: Long,
    )

    data class GradingResp(
        @get:JsonProperty("executor") val executor: String,
        @get:JsonProperty("queued") val queued: Int,
        @get:JsonProperty("running") val running: Int,
        // Without this, an executor that is *down* renders identically to one that is idle — both
        // are "0 queued, 0 running" — which is the wrong direction for an operations panel to fail
        // in. Taken from the same check the version list uses, so the two cannot contradict.
        @get:JsonProperty("reachable") val reachable: Boolean,
    )

    data class DiskResp(
        @get:JsonProperty("free_gb") val freeGb: Long,
        @get:JsonProperty("total_gb") val totalGb: Long,
    )

    data class Resp(
        @get:JsonProperty("jvm") val jvm: JvmResp,
        @get:JsonProperty("db_pool") val dbPool: DbPoolResp?,
        @get:JsonProperty("schema") val schema: SchemaResp,
        @get:JsonProperty("grading") val grading: List<GradingResp>,
        @get:JsonProperty("disk") val disk: DiskResp,
    )

    @Secured("ROLE_ADMIN")
    @GetMapping("/admin/operating-info")
    fun controller(caller: EasyUser): Resp {
        log.debug { "Operating info for ${caller.id}" }
        return operatingInfoService.get()
    }
}

@Service
class OperatingInfoService(
    private val dataSource: DataSource,
    private val autoGradeScheduler: AutoGradeScheduler,
    // For executor reachability, which it already establishes and caches for the version list.
    private val versionsService: VersionsService,
) {

    fun get() = OperatingInfoController.Resp(
        jvm = jvm(),
        dbPool = dbPool(),
        schema = schema(),
        grading = grading(),
        disk = disk(),
    )

    private fun jvm(): OperatingInfoController.JvmResp {
        val runtime = ManagementFactory.getRuntimeMXBean()
        val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage
        return OperatingInfoController.JvmResp(
            startedAt = java.time.Instant.ofEpochMilli(runtime.startTime).toString(),
            uptimeSec = runtime.uptime / 1000,
            heapUsedMb = heap.used / MB,
            heapMaxMb = if (heap.max > 0) heap.max / MB else -1,
            threads = ManagementFactory.getThreadMXBean().threadCount,
            javaVersion = System.getProperty("java.version") ?: "unknown",
        )
    }

    /**
     * Null when the pool is not Hikari — which it always is under Spring Boot, but the cast is not
     * worth an exception on a diagnostic page if that ever changes.
     */
    private fun dbPool(): OperatingInfoController.DbPoolResp? {
        val hikari = dataSource as? HikariDataSource ?: return null
        val pool = hikari.hikariPoolMXBean ?: return null
        return OperatingInfoController.DbPoolResp(
            active = pool.activeConnections,
            idle = pool.idleConnections,
            waiting = pool.threadsAwaitingConnection,
            max = hikari.maximumPoolSize,
        )
    }

    /**
     * The last changeset Liquibase applied, straight from `databasechangelog`.
     *
     * Worth having on the page: a deploy that half-applies leaves core running against a schema it
     * was not built for, and nothing else in the UI would say so.
     */
    private fun schema(): OperatingInfoController.SchemaResp = transaction {
        var changeset: String? = null
        var filename: String? = null
        var appliedAt: String? = null
        var total = 0L

        TransactionManager.current().exec(
            """
            SELECT id, filename, dateexecuted, (SELECT count(*) FROM databasechangelog) AS total
            FROM databasechangelog ORDER BY orderexecuted DESC LIMIT 1
            """.trimIndent()
        ) { rs ->
            if (rs.next()) {
                changeset = rs.getString("id")
                filename = rs.getString("filename")
                appliedAt = rs.getTimestamp("dateexecuted")?.toInstant()?.toString()
                total = rs.getLong("total")
            }
        }

        OperatingInfoController.SchemaResp(changeset, filename, appliedAt, total)
    }

    /**
     * Queue depth per executor, from the scheduler's own memory joined to the executor names, plus
     * whether the executor is actually answering.
     *
     * An executor with a row but no entry in the scheduler is reported as idle rather than omitted:
     * the two can disagree for up to a minute after the rows change, and hiding that would hide
     * exactly the state worth seeing.
     *
     * Reachability is reused from [VersionsService] rather than pinged again. That is one HTTP call
     * per executor saved, and — more to the point — it means this panel and the version list on the
     * same page cannot disagree about whether an executor is up. The cost is that a change of state
     * takes up to that cache's TTL to appear here, which for an operations page a human is reading
     * is a fair trade.
     */
    private fun grading(): List<OperatingInfoController.GradingResp> {
        val load = autoGradeScheduler.currentLoad()
        val names = transaction {
            Executor.selectAll().associate { it[Executor.id].value to it[Executor.name] }
        }
        val reachableByName = versionsService.executors().associate { it.name to it.reachable }

        return names.entries.sortedBy { it.value }.map { (id, name) ->
            val executorLoad = load[id]
            OperatingInfoController.GradingResp(
                executor = name,
                queued = executorLoad?.waiting ?: 0,
                running = executorLoad?.active ?: 0,
                reachable = reachableByName[name] ?: false,
            )
        }
    }

    /**
     * The filesystem core is running from. Grading builds a container image per submission, so this
     * host fills up in a way an ordinary web application's does not.
     */
    private fun disk(): OperatingInfoController.DiskResp {
        val root = File("/")
        return OperatingInfoController.DiskResp(
            freeGb = root.usableSpace / GB,
            totalGb = root.totalSpace / GB,
        )
    }

    companion object {
        private const val MB = 1024L * 1024L
        private const val GB = MB * 1024L
    }
}
