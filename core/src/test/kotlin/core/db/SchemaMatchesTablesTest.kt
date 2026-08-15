package core.db

import core.testing.ExposedTables
import core.testing.IntegrationTest
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The schema Liquibase builds and the tables Exposed thinks exist are the same thing.
 *
 * This is the cheapest high-value backend test available, and it is **maintenance-free forever** —
 * no per-changeset upkeep, ever. It catches the migration mistake this codebase is actually shaped
 * to make, in both directions:
 *
 * - **A column added to `Tables.kt` with no changeset.** Works on the developer's machine, because
 *   their database was migrated by hand at some point, and fails at every deploy.
 * - **A changeset with no `Tables.kt` update.** Migration succeeds, the column exists, and Exposed
 *   never reads it — so the feature is silently half-built.
 * - **Nullability drift.** Making a column `NOT NULL` in a changeset without dropping `.nullable()`
 *   (or the reverse) produces a type that lies. Changeset `020826-1` — `anonymous_autoassess_template`
 *   moving from nullable to `default("")` — is exactly this shape, and it rewrote production rows.
 *
 * Deliberately reports rather than fails on columns the database has and Exposed does not: those
 * are usually legacy, removing one is a separate decision, and a test that fails until somebody
 * does unrelated archaeology is a test that gets muted.
 */
@IntegrationTest
class SchemaMatchesTablesTest {

    private data class DbColumn(val nullable: Boolean, val dataType: String)

    /**
     * Nullability disagreements that predate this test, each with why it is not fixed here.
     *
     * All three are the dangerous direction — the schema permits null and Kotlin promises it will
     * not be — so a null row makes a read throw somewhere unrelated to where the row was written.
     * None is fixed in the same commit as this test, because each needs a changeset that rewrites
     * production rows, and that is a different review from adding a guard. Tracked as **EZ-1771**.
     *
     * This map can only shrink: an entry that no longer disagrees fails the test below, so fixing
     * one forces deleting its line rather than leaving a stale exemption behind. An entry with a
     * blank reason is rejected — an exception nobody has to justify is just a hole.
     */
    private val knownNullabilityDrift = mapOf<String, String>(
        "article_version.title" to
                "Created without NOT NULL in v3 (EZ-1573); core always writes a title, so nothing " +
                "is expected to be null, but nothing enforces it either. Cheapest of the three to " +
                "fix: the table is weeks old and small.",
        "student_course_access.created_at" to
                "Long-lived table. The column is nullable in the schema and non-null in Kotlin; " +
                "whether any production row is actually null is unknown and has to be checked " +
                "before a NOT NULL constraint can be added, since the changeset would fail on one.",
        "teacher_course_access.created_at" to
                "Same as student_course_access.created_at, and should be fixed in the same changeset.",
    )

    private fun liveSchema(): Map<String, Map<String, DbColumn>> = transaction {
        val out = mutableMapOf<String, MutableMap<String, DbColumn>>()
        exec(
            """
            SELECT table_name, column_name, is_nullable, data_type
            FROM information_schema.columns
            WHERE table_schema = 'public'
            """
        ) { rs ->
            while (rs.next()) {
                out.getOrPut(rs.getString(1)) { mutableMapOf() }[rs.getString(2)] =
                    DbColumn(rs.getString(3) == "YES", rs.getString(4))
            }
        }
        out
    }

    @Test
    fun `every Exposed table exists in the migrated schema`() {
        val live = liveSchema()
        val missing = ExposedTables.all()
            .map { it.tableName }
            .filterNot { it.lowercase() in live.keys.map(String::lowercase) }
            .sorted()

        assertTrue(missing.isEmpty()) {
            "These tables are declared in core.db but do not exist after Liquibase runs:\n" +
                    missing.joinToString("\n") { "  $it" } +
                    "\n\nEither the changeset that creates one is missing, or the Exposed object names " +
                    "a table that was renamed or dropped. Every read through it fails at runtime today."
        }
    }

    @Test
    fun `every Exposed column exists, with matching nullability`() {
        val live = liveSchema().mapKeys { it.key.lowercase() }

        val missing = mutableListOf<String>()
        val nullabilityMismatch = mutableListOf<String>()

        ExposedTables.all().forEach { table ->
            val liveColumns = live[table.tableName.lowercase()]?.mapKeys { it.key.lowercase() } ?: return@forEach
            table.columns.forEach { column ->
                val key = "${table.tableName}.${column.name}"
                val liveColumn = liveColumns[column.name.lowercase()]
                if (liveColumn == null) {
                    missing += key
                } else if (liveColumn.nullable != column.columnType.nullable && key !in knownNullabilityDrift) {
                    nullabilityMismatch += "$key: " +
                            "Exposed says ${if (column.columnType.nullable) "nullable" else "NOT NULL"}, " +
                            "database says ${if (liveColumn.nullable) "nullable" else "NOT NULL"} " +
                            "(${liveColumn.dataType})"
                }
            }
        }

        assertTrue(missing.isEmpty()) {
            "These columns are declared in core.db but do not exist after Liquibase runs:\n" +
                    missing.joinToString("\n") { "  $it" } +
                    "\n\nThe usual cause is adding a column to Tables.kt and forgetting the changeset. " +
                    "It works locally against a hand-migrated database and fails on every deploy."
        }

        assertTrue(nullabilityMismatch.isEmpty()) {
            "These columns disagree with the schema about nullability:\n" +
                    nullabilityMismatch.joinToString("\n") { "  $it" } +
                    "\n\nThis is the quietest of the three drifts. Exposed claiming a column is nullable " +
                    "when it is NOT NULL means an insert omitting it fails at runtime; the reverse means " +
                    "reads hand out a non-null type over data that can be null, and the NPE surfaces " +
                    "somewhere unrelated. Fix whichever side is wrong — and if it is the schema, that " +
                    "is a changeset that rewrites rows, so read doc/testing.md on migration tests first."
        }
    }

    /**
     * The exception list can only shrink.
     *
     * Without this, fixing a drift leaves its exemption behind, and the next person reads a list of
     * three problems of which one is imaginary. Worse, a stale entry silently exempts the column
     * again if it ever regresses. So: every entry must still describe a real disagreement, and
     * every entry must carry a reason.
     */
    @Test
    fun `the known-drift exceptions are all still real, and all still justified`() {
        val live = liveSchema().mapKeys { it.key.lowercase() }

        val actualDrift = ExposedTables.all().flatMap { table ->
            val liveColumns = live[table.tableName.lowercase()]?.mapKeys { it.key.lowercase() }.orEmpty()
            table.columns.mapNotNull { column ->
                val liveColumn = liveColumns[column.name.lowercase()]
                if (liveColumn != null && liveColumn.nullable != column.columnType.nullable)
                    "${table.tableName}.${column.name}" else null
            }
        }.toSet()

        val fixed = (knownNullabilityDrift.keys - actualDrift).sorted()
        assertTrue(fixed.isEmpty()) {
            "These columns are listed in knownNullabilityDrift but no longer disagree:\n" +
                    fixed.joinToString("\n") { "  $it" } +
                    "\n\nSomebody fixed them — delete their entries. A list that outlives its problems " +
                    "stops being read, and a stale entry would silently exempt the column if it " +
                    "regressed."
        }

        val unjustified = knownNullabilityDrift.filterValues { it.isBlank() }.keys.sorted()
        assertTrue(unjustified.isEmpty()) {
            "These knownNullabilityDrift entries have no reason:\n" +
                    unjustified.joinToString("\n") { "  $it" } +
                    "\n\nAn exception nobody has to justify is just a hole with extra steps."
        }
    }

    /**
     * Columns the database has and no Exposed table declares.
     *
     * A **ratchet against a recorded set** rather than a printed report. The first version of this
     * only printed, which made it a test that could not fail — and doc/testing.md's own rule is
     * that such a test is worse than none, because it is still counted. The list also happens to be
     * exactly the kind of thing nobody reads in a passing build's log.
     *
     * Failing outright on the existing entry would have been the other wrong answer: removing a
     * legacy column is its own decision with its own migration, and a test that stays red until
     * somebody does unrelated archaeology gets muted. So: the known ones are recorded, a *new* one
     * fails, and a fixed one must be deleted from the list.
     *
     * A new undeclared column is worth catching. It usually means a changeset landed without the
     * `Tables.kt` change that was supposed to accompany it, so the feature is half-built and the
     * column is dead weight nothing reads.
     */
    private val knownUndeclaredColumns = setOf<String>(
        // Legacy. Moodle usernames are read through StudentMoodlePendingAccess and the
        // student_moodle_* tables; this column on `account` predates that and nothing reads it.
        // Dropping it is a migration and a decision, not a side effect of adding this test.
        "account.moodle_username",
    )

    @Test
    fun `no new column exists in the database without an Exposed declaration`() {
        val live = liveSchema()
        val declared = ExposedTables.all().associate { table ->
            table.tableName.lowercase() to table.columns.map { it.name.lowercase() }.toSet()
        }

        val undeclared = live.flatMap { (tableName, columns) ->
            val known = declared[tableName.lowercase()] ?: return@flatMap emptyList()
            columns.keys.filterNot { it.lowercase() in known }.map { "$tableName.$it" }
        }.toSet()

        val appeared = (undeclared - knownUndeclaredColumns).sorted()
        assertTrue(appeared.isEmpty()) {
            "These columns exist in the schema and no Exposed table declares them:\n" +
                    appeared.joinToString("\n") { "  $it" } +
                    "\n\nUsually this means a changeset landed without the Tables.kt change meant to " +
                    "go with it — so nothing reads the column and the feature is half-built. Add it " +
                    "to the table object, or, if it is deliberately unread, record it in " +
                    "knownUndeclaredColumns in this test with the reason."
        }

        val gone = (knownUndeclaredColumns - undeclared).sorted()
        assertTrue(gone.isEmpty()) {
            "These entries in knownUndeclaredColumns no longer describe anything:\n" +
                    gone.joinToString("\n") { "  $it" } +
                    "\n\nThe column was declared or dropped — delete the entry, so the list keeps " +
                    "meaning what it says."
        }
    }
}
