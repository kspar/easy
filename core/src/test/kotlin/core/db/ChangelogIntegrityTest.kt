package core.db

import core.testing.IntegrationTest
import core.testing.TestDatabase
import liquibase.Liquibase
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.DriverManager

/**
 * Properties of the changelog itself, as opposed to the schema it produces.
 *
 * The one that matters most is the checksum baseline. Liquibase records a checksum per applied
 * changeset and refuses to run if a changeset it has already applied has since been edited. On a
 * developer's machine that is an inconvenience; on a deployed environment it means **core does not
 * start**, because the migration runs inside the Spring context and a failed migration fails the
 * context. That is the worst failure mode this repo has — a release that cannot be rolled forward
 * or, since migrations are forward-only, easily back.
 *
 * Editing an applied changeset is also an easy mistake to make innocently: fixing a typo in a
 * `<comment>`, reformatting some SQL, tidying an indent. None of those look dangerous.
 *
 * So the checksums live in a committed file, and changing one is a reviewable line in a diff rather
 * than a discovery made at deploy time.
 */
@IntegrationTest
class ChangelogIntegrityTest {

    private val baselineResource = "/db/changelog-checksums.txt"
    private val changelog = "db/changelog.xml"

    /**
     * Every changeset Liquibase has applied, as `filename::id::author::checksum`.
     *
     * **`filename` is part of the key, not decoration.** Liquibase identifies a changeset by
     * (id, author, filename), and the ids here are date-derived — `010324-1`, `220226-1` — across
     * three authors and five changelog files. A future `v5.xml` reusing `010324-1` by `priit` is an
     * ordinary coincidence, and without the filename the two rows would collapse onto one key: only
     * one would ever be compared, editing the other would be invisible, and the added/removed
     * checks would be fooled the same way. It also makes the ordering total, so the generated
     * baseline does not shuffle between runs.
     */
    private fun appliedChecksums(): List<String> = transaction {
        val rows = mutableListOf<String>()
        exec(
            """
            SELECT filename, id, author, md5sum FROM databasechangelog
            WHERE md5sum IS NOT NULL ORDER BY filename, id, author
            """
        ) { rs ->
            while (rs.next()) {
                rows += "${rs.getString(1)}::${rs.getString(2)}::${rs.getString(3)}::${rs.getString(4)}"
            }
        }
        rows
    }

    @Test
    fun `no applied changeset has been edited since it was written`() {
        val actual = appliedChecksums()

        assertTrue(actual.size >= 100) {
            "Only ${actual.size} applied changesets found — the query is wrong, and this test would " +
                    "then pass against nothing."
        }

        // Read through the classpath rather than as a relative File. A relative path resolves only
        // when the working directory happens to be `core/` — which Gradle gives and a plain JUnit
        // run configuration does not. The old version did not merely fail there: it decided the
        // baseline was missing and *wrote a new one* at a stray path, which is the worst of the
        // available behaviours, because it would have looked like the guard had been set up.
        val baselineText = javaClass.getResource(baselineResource)?.readText()
        if (baselineText == null) {
            val regenerated = File("build/changelog-checksums.txt")
            regenerated.parentFile.mkdirs()
            regenerated.writeText(actual.joinToString("\n", postfix = "\n"))
            error(
                "No checksum baseline on the classpath at $baselineResource.\n" +
                        "A fresh one has been written to ${regenerated.absolutePath} — review it and copy " +
                        "it to core/src/test/resources$baselineResource, then commit it. It is written to " +
                        "the build directory rather than straight into the source tree so that a run from " +
                        "an unexpected working directory cannot leave a stray file behind."
            )
        }

        val expected = baselineText.lines().filter { it.isNotBlank() }
        val expectedById = expected.associate { it.substringBeforeLast("::") to it.substringAfterLast("::") }
        val actualById = actual.associate { it.substringBeforeLast("::") to it.substringAfterLast("::") }

        val changed = actualById.mapNotNull { (id, sum) ->
            val was = expectedById[id] ?: return@mapNotNull null
            if (was != sum) "  $id\n      was $was\n      now $sum" else null
        }

        assertTrue(changed.isEmpty()) {
            "These changesets have been EDITED since they were applied:\n" +
                    changed.joinToString("\n") +
                    "\n\nLiquibase refuses to run when an applied changeset's checksum has changed, and " +
                    "because migrations run inside the Spring context, that means core does not start " +
                    "on any environment that has already applied it — production included.\n\n" +
                    "If the edit was cosmetic (a comment, whitespace, reformatting) it is still a new " +
                    "checksum and still fatal. Revert it, or add a <validCheckSum> to that changeset " +
                    "the way v4.xml's 260225-1 and 220226-1 do, and update " +
                    "core/src/test/resources$baselineResource in the same commit."
        }

        val removed = (expectedById.keys - actualById.keys).sorted()
        assertTrue(removed.isEmpty()) {
            "These changesets are in the baseline but were not applied:\n" +
                    removed.joinToString("\n") { "  $it" } +
                    "\n\nA changeset was deleted or renamed. Deleting one that environments have already " +
                    "run does not un-apply it — it just means the schema and the changelog no longer " +
                    "describe the same thing. If it was deliberate, update core/src/test/resources$baselineResource."
        }

        // New changesets are expected and are not a failure; they just need recording, so that the
        // next edit to one of them is caught.
        val added = (actualById.keys - expectedById.keys).sorted()
        assertTrue(added.isEmpty()) {
            "These changesets are applied but not in the baseline:\n" +
                    added.joinToString("\n") { "  $it" } +
                    "\n\nThat is expected when you add a migration. Append them to core/src/test/resources$baselineResource " +
                    "and commit it — until you do, editing one later would go unnoticed. Delete the " +
                    "file and re-run this test to regenerate it wholesale."
        }
    }

    /**
     * Running the migration twice must be a no-op.
     *
     * A changeset that is not idempotent and carries no precondition applies a second time on the
     * next deploy — and every deploy after that. Runs against its own database so the suite's schema
     * is untouched.
     */
    @Test
    fun `a second update applies nothing`() = withScratchDatabase("easyems_changelog_test") { conn ->
        Liquibase(changelog, ClassLoaderResourceAccessor(), conn).update("schema-only")
        val afterFirst = countApplied(conn)

        Liquibase(changelog, ClassLoaderResourceAccessor(), conn).update("schema-only")
        val afterSecond = countApplied(conn)

        assertEquals(afterFirst, afterSecond) {
            "A second Liquibase run applied ${afterSecond - afterFirst} more changeset(s). One of them " +
                    "is not idempotent and has no precondition, so it will re-apply on every deploy."
        }
    }

    /**
     * The context guard, which is the only thing standing between production and the local fixtures.
     *
     * `testdata.xml` inserts accounts and courses at hardcoded ids in the 9000s. Liquibase reads
     * *no* contexts as "run everything", so before the contexts were set, those inserts applied
     * wherever the schema did. Against an imported production dump that died on a duplicate
     * `exercise_version` id — and a failed migration means core does not start.
     *
     * Today the production path passes `schema-only`. This asserts that it really excludes the
     * fixtures, rather than that somebody remembered to keep it correct.
     */
    @Test
    fun `schema-only builds the schema and inserts no test data`() =
        withScratchDatabase("easyems_schemaonly_test") { conn ->
            Liquibase(changelog, ClassLoaderResourceAccessor(), conn).update("schema-only")

            assertEquals(0, countRows(conn, "account")) {
                "The schema-only context inserted account rows. testdata.xml's fixtures are reachable " +
                        "from the production migration path, which is exactly what the contexts exist " +
                        "to prevent."
            }
            assertEquals(0, countRows(conn, "course")) { "The schema-only context inserted course rows." }
            assertTrue(countApplied(conn) > 100) { "schema-only applied almost nothing; it should build the whole schema." }
        }

    @Test
    fun `the testdata context does insert the local fixtures`() =
        withScratchDatabase("easyems_testdata_test") { conn ->
            Liquibase(changelog, ClassLoaderResourceAccessor(), conn).update("testdata")

            assertTrue(countRows(conn, "account") > 0) {
                "The testdata context inserted no accounts. Every developer's `docker compose up db` " +
                        "relies on these fixtures, and nothing else exercises them."
            }
            assertTrue(countRows(conn, "course") > 0) { "The testdata context inserted no courses." }
        }

    // --- helpers -------------------------------------------------------------------------------

    private fun countApplied(conn: JdbcConnection): Int =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT count(*) FROM databasechangelog").use { it.next(); it.getInt(1) }
        }

    private fun countRows(conn: JdbcConnection, table: String): Int =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT count(*) FROM \"$table\"").use { it.next(); it.getInt(1) }
        }

    /**
     * Runs [body] against a freshly created, empty database on the same server, then drops it.
     *
     * These tests need to migrate from nothing, repeatedly, which the suite's shared schema cannot
     * offer. Names all end in `_test` for the same reason everything else here does.
     */
    private fun withScratchDatabase(name: String, body: (JdbcConnection) -> Unit) {
        require(name.endsWith("_test")) { "Scratch database '$name' must end in _test." }
        val adminUrl = TestDatabase.jdbcUrl
        val scratchUrl = adminUrl.substringBeforeLast('/') + "/" + name

        DriverManager.getConnection(adminUrl, TestDatabase.username, TestDatabase.password).use { admin ->
            admin.createStatement().use {
                it.execute("DROP DATABASE IF EXISTS \"$name\"")
                it.execute("CREATE DATABASE \"$name\"")
            }
        }
        try {
            DriverManager.getConnection(scratchUrl, TestDatabase.username, TestDatabase.password).use { c ->
                body(JdbcConnection(c))
            }
        } finally {
            DriverManager.getConnection(adminUrl, TestDatabase.username, TestDatabase.password).use { admin ->
                admin.createStatement().use { it.execute("DROP DATABASE IF EXISTS \"$name\"") }
            }
        }
    }
}
