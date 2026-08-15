package core.testing

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import java.net.URI

private const val DISPOSABLE_DB_SUFFIX = "_test"
private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "::1")

/**
 * Refuse to run a destructive statement unless the target is obviously a throwaway local test
 * database.
 *
 * Inherited from the guard EZ-1717 added around Liquibase `dropAll()`. That call is gone —
 * the schema is now built once by the production `SpringLiquibase` bean and emptied between tests
 * by [truncateAll] — but the hazard it protected against is unchanged: with `EASY_TEST_JDBC_URL`
 * set, the datasource is whatever a human typed, and `TRUNCATE` over every table is as final as a
 * drop. It fails closed, before anything is deleted.
 *
 * These classes live in the test source set and are not packaged into the bootJar, so they cannot
 * run in a deployed environment. This protects the case that *is* reachable: a developer or CI job
 * pointed at the wrong database.
 *
 * Widen [LOCAL_HOSTS] only deliberately. In particular, if the CI job is ever moved to run *inside*
 * a container, the database becomes reachable as a service alias rather than on localhost and this
 * will reject it — which is the guard working, not a bug to route around.
 */
internal fun assertDisposableDatabase(url: String) {
    val uri = runCatching { URI(url.removePrefix("jdbc:")) }.getOrNull()
        ?: error("Refusing to modify schema: could not parse JDBC URL '$url'.")

    val host = uri.host?.lowercase()
    val database = uri.path.orEmpty().trimStart('/').substringBefore('?')

    check(host in LOCAL_HOSTS) {
        "Refusing to empty tables on non-local host '$host' (from '$url'). " +
                "Tests wipe the database they connect to; point them at a local throwaway. " +
                "If a remote host is genuinely intended, change LOCAL_HOSTS in DatabaseReset.kt deliberately."
    }
    check(database.endsWith(DISPOSABLE_DB_SUFFIX)) {
        "Refusing to empty tables on database '$database' (from '$url'): it does not end in " +
                "'$DISPOSABLE_DB_SUFFIX'. Tests wipe the database they connect to, so only " +
                "throwaway databases are allowed. Unset EASY_TEST_JDBC_URL to use the container, " +
                "or point it at a database whose name ends in '_test'."
    }
}

/**
 * Empty every table, in one statement.
 *
 * One `TRUNCATE a, b, … CASCADE` rather than a delete per table, so referential integrity never has
 * to be reasoned about and the order of the list does not matter. `RESTART IDENTITY` makes
 * sequence-assigned ids deterministic per *test* rather than per run — worth having, because a test
 * that passes only because it ran second is a test that fails the day someone adds one above it.
 *
 * The list comes from the Exposed table objects, not from `information_schema`: a table added to
 * Tables.kt is then emptied automatically, and a table that exists only in the schema is
 * deliberately not this function's problem — SchemaMatchesTablesTest is what notices that.
 *
 * `databasechangelog` and `databasechangeloglock` are untouched, because they are not Exposed
 * tables. That is load-bearing: truncating them would make Liquibase re-run every changeset.
 *
 * ### Why not a transaction rolled back per test
 *
 * The conventional answer, and wrong here. Exposed binds its transaction to a `ThreadLocal`, so an
 * outer transaction opened by the test is only joined by code running on the *same* thread. MockMvc
 * qualifies; a great deal of this application does not — `AutoGradeScheduler` on its own pool,
 * `submitAndAwait` as a suspend function, `AutoGradeStatusObserver`, the Moodle sync, and any
 * future `RANDOM_PORT` test where Tomcat's worker thread has no ambient transaction. In those cases
 * the writes commit for real, the rollback discards nothing, and the leak surfaces as a *different*
 * test failing later.
 *
 * A mechanism that appears to work and quietly does not, under conditions the author has to
 * remember, is exactly the failure class doc/testing.md is about. One regime that is always
 * correct, for 5–20 ms a test, is the better trade.
 */
fun truncateAll(jdbcUrl: String) {
    assertDisposableDatabase(jdbcUrl)

    val tables = ExposedTables.all().joinToString(", ") { "\"${it.tableName}\"" }
    transaction {
        exec("TRUNCATE $tables RESTART IDENTITY CASCADE")
    }
}

/**
 * Empties the database *before* each test.
 *
 * Before rather than after, for two reasons: a failing test leaves its rows behind for whoever
 * wants to `psql` into the container and look, and no test can come to depend on its predecessor
 * having cleaned up.
 *
 * Registered once by the `@IntegrationTest` meta-annotation so that no test class can forget it.
 */
class DatabaseResetExtension : BeforeEachCallback {
    override fun beforeEach(context: ExtensionContext) {
        truncateAll(TestDatabase.jdbcUrl)
    }
}
