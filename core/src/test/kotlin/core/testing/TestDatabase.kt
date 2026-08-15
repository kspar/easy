package core.testing

import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.test.context.support.TestPropertySourceUtils
import org.testcontainers.containers.PostgreSQLContainer

/**
 * The PostgreSQL the test suite runs against.
 *
 * Started once per JVM and never stopped — the JVM exiting kills it and Testcontainers' Ryuk
 * sidecar reaps it. Deliberately *not* JUnit's `@Testcontainers`/`@Container`, which starts and
 * stops a container per class and would pay the startup cost once per test class.
 *
 * Why a container rather than the postgres service container EZ-1715 originally proposed: a
 * service container fixes CI and leaves a laptop exactly as it was — `createdb easyems_test` by
 * hand plus a config file you write yourself. That ritual is why the nine database-backed tests
 * had stopped running in both places. This way `git clone && ./gradlew test` works anywhere
 * Docker does, and there is one postgres version rather than two that can disagree.
 *
 * Same image tag as docker-compose.yml. Change both together.
 */
object TestDatabase {

    /**
     * Escape hatch for a machine without Docker, or for debugging against a database you want to
     * `psql` into afterwards:
     *
     *     EASY_TEST_JDBC_URL=jdbc:postgresql://localhost:5432/easyems_test ./gradlew :core:test
     *
     * assertDisposableDatabase() still applies, so the name has to end in `_test`.
     */
    private val externalUrl: String? = System.getenv("EASY_TEST_JDBC_URL")

    private val container: PostgreSQLContainer<*>? =
        if (externalUrl != null) null
        else PostgreSQLContainer("postgres:16")
            // Mandatory, not cosmetic. Testcontainers' default database name is `test`, and
            // "test".endsWith("_test") is false — assertDisposableDatabase would fail closed on
            // every run, which reads like a broken guard rather than a missing setting.
            .withDatabaseName("easyems_test")
            .withUsername("easyems")
            // Honoured only if the developer has opted in via ~/.testcontainers.properties.
            // CI ignores it and gets a fresh container, which is what CI should get.
            .withReuse(true)
            .also { it.start() }

    /**
     * A plain `jdbc:postgresql://localhost:<mapped>/easyems_test`.
     *
     * Note what this is NOT: the `jdbc:tc:postgresql:16:///easyems_test` scheme Testcontainers
     * also offers. assertDisposableDatabase() parses the URL with [java.net.URI] after stripping
     * `jdbc:`, and `tc:postgresql:16:///easyems_test` has a null host — so the guard fails closed
     * and takes the whole suite down with a message about a non-local host. Keep it a real URL.
     */
    val jdbcUrl: String get() = externalUrl ?: container!!.jdbcUrl
    val username: String get() = System.getenv("EASY_TEST_DB_USER") ?: container!!.username
    val password: String get() = System.getenv("EASY_TEST_DB_PASSWORD") ?: container!!.password
}

/**
 * Points the application's datasource at [TestDatabase], overriding the fallback values in
 * core/src/test/resources/application.yaml.
 *
 * An initializer rather than `@DynamicPropertySource`, because that annotation is only discovered
 * on the *test class* itself — it does not work from a `@Configuration` bean, and putting it on
 * every test class is precisely the per-class boilerplate `@IntegrationTest` exists to remove.
 * An initializer composes into the meta-annotation and runs before any bean is created, which is
 * what the datasource needs.
 *
 * Nothing here migrates the schema. The production `SpringLiquibase` bean in
 * core/conf/DatabaseConf.kt does that at context startup, against a container that starts empty —
 * so the suite exercises the same migration path a deploy does, rather than a parallel one in the
 * test source set that could drift from it. That replaced an `InitTestDatabase` which ran
 * Liquibase `dropAll()` + `update("")` itself.
 *
 * One consequence worth knowing: `update("")` reads empty contexts as *run everything*, so the
 * old test database was silently seeded with all of testdata.xml — accounts `dev-student` and
 * `kspar`, courses in the 9000s, the mock executor row. The production bean runs `schema-only`,
 * so the database now starts genuinely empty and every fixture is explicit.
 */
class TestDatabaseInitializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
    override fun initialize(applicationContext: ConfigurableApplicationContext) {
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
            applicationContext,
            "spring.datasource.jdbc-url=${TestDatabase.jdbcUrl}",
            "spring.datasource.username=${TestDatabase.username}",
            "spring.datasource.password=${TestDatabase.password}",
        )
    }
}
