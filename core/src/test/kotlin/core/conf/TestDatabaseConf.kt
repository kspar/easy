package core.conf

import jakarta.annotation.PostConstruct
import liquibase.Liquibase
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import java.net.URI
import javax.sql.DataSource


/**
 * SpringBootTest(classes = [EasyCoreApp::class]) loads all classes such as Moodle sync cron job, which require database
 * schema. Doing Liquibase update in BeforeEach/BeforeAll is too late for initial setup.
 *
 * Init db with Liquibase schema.
 */
@Configuration
class InitTestDatabase(val dataSource: DataSource) {
    @Value("\${easy.core.liquibase.changelog}")
    private lateinit var changelogFile: String

    @PostConstruct
    fun init() {
        Database.connect(dataSource)
        TransactionManager.manager.defaultMaxAttempts = 6

        dropAndUpdateSchema(changelogFile, JdbcConnection(dataSource.connection))
    }
}

private const val DISPOSABLE_DB_SUFFIX = "_test"
private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "::1")

/**
 * Refuse to run a destructive Liquibase operation unless the target is obviously a throwaway
 * local test database.
 *
 * Everything below calls Liquibase `dropAll()`, which deletes every object in the schema. The
 * datasource comes from `core/src/test/resources/application.yaml` — gitignored, hand-written
 * per machine, and therefore the one place a typo could point a test run at a real database.
 * This check is the guard rail: it fails closed, before anything is dropped.
 *
 * These classes live in the test source set and are not packaged into the bootJar, so they
 * cannot run in a deployed environment. This protects the case that *is* reachable: a
 * developer or CI job with a misconfigured test yaml.
 */
internal fun assertDisposableDatabase(connection: JdbcConnection) {
    val url = connection.url
        ?: error("Refusing to drop schema: could not read the JDBC URL, so it cannot be verified as a test database.")

    // "jdbc:postgresql://host:port/db?params" -> parse the part after the jdbc: scheme.
    val uri = runCatching { URI(url.removePrefix("jdbc:")) }.getOrNull()
        ?: error("Refusing to drop schema: could not parse JDBC URL '$url'.")

    val host = uri.host?.lowercase()
    val database = uri.path.orEmpty().trimStart('/').substringBefore('?')

    check(host in LOCAL_HOSTS) {
        "Refusing to drop schema on non-local host '$host' (from '$url'). " +
                "Tests wipe the database they connect to; point them at a local throwaway. " +
                "If a remote host is genuinely intended, change LOCAL_HOSTS in TestDatabaseConf.kt deliberately."
    }
    check(database.endsWith(DISPOSABLE_DB_SUFFIX)) {
        "Refusing to drop schema on database '$database' (from '$url'): it does not end in " +
                "'$DISPOSABLE_DB_SUFFIX'. Tests wipe the database they connect to, so only " +
                "throwaway databases are allowed. Create one and point " +
                "core/src/test/resources/application.yaml at it:\n" +
                "  docker exec easy-db-1 psql -U easyems -d postgres -c \"create database easyems_test;\""
    }
}

fun dropAll(changelogFile: String, connection: JdbcConnection) {
    assertDisposableDatabase(connection)
    Liquibase(changelogFile, ClassLoaderResourceAccessor(), connection).dropAll()
}

fun dropAndUpdateSchema(changelogFile: String, connection: JdbcConnection) {
    assertDisposableDatabase(connection)
    val lb = Liquibase(changelogFile, ClassLoaderResourceAccessor(), connection)
    lb.dropAll()
    lb.update("")
}
