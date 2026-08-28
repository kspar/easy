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
     * Nullability disagreements not fixed yet, each with why not.
     *
     * This map can only shrink: an entry that no longer disagrees fails the test below, so fixing
     * one forces deleting its line rather than leaving a stale exemption behind. An entry with a
     * blank reason is rejected — an exception nobody has to justify is just a hole.
     *
     * **It is empty, and that is what the mechanism is for.** The three it opened with — EZ-1771,
     * `article_version.title` and both `*_course_access.created_at` — were closed by changesets
     * `210826-1` and `210826-2`, and the entries had to go in the same commit because leaving them
     * would have failed this test. Which is the point: the exemption list could not outlive the
     * exemption.
     *
     * What answering it required was a measurement, not a judgement. The title column had no nulls at
     * all; the two `created_at` columns had 32 and 40, so they needed a backfill, and a changeset
     * that only added the constraint would have stopped core from starting.
     */
    private val knownNullabilityDrift = mapOf<String, String>()

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

    /**
     * The primary-key columns the database actually has, per table, **in key order**.
     *
     * The ordering is `unnest(i.indkey) WITH ORDINALITY`, not `ORDER BY a.attnum`, and the difference
     * is real: `attnum` is a column's position in the *table*, while `indkey` is its position in the
     * *key*, and the two part company as soon as a key is (re)declared by `addPrimaryKey` rather than
     * by `createTable`. Half this schema's composite keys are — changeset `251119-1` adds
     * `columnNames="course_id, teacher_id"` to a table whose columns are declared teacher-first, and
     * `191021-1` and `201021-2` do the same for three more.
     *
     * The first draft of this check used `a.attnum = ANY (i.indkey)` with `ORDER BY a.attnum`, which is
     * self-consistent enough to look right: it reported five mismatches, all of them real. It was the
     * *false negatives* that gave it away — `student_course_group_access` and
     * `student_moodle_pending_course_group_access` passed, because for those two the table's column
     * order happens to equal what Exposed declares, so the wrong ordering agreed with the wrong
     * declaration. The changesets above are what settled which of the two queries to believe.
     */
    private fun livePrimaryKeys(): Map<String, List<String>> = transaction {
        val out = mutableMapOf<String, MutableList<String>>()
        exec(
            """
            SELECT c.relname AS table_name, a.attname AS column_name
            FROM pg_index i
                JOIN pg_class c ON c.oid = i.indrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                CROSS JOIN LATERAL unnest(i.indkey) WITH ORDINALITY AS k(attnum, ord)
                JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = k.attnum
            WHERE i.indisprimary AND n.nspname = 'public'
            ORDER BY c.relname, k.ord
            """
        ) { rs ->
            while (rs.next()) {
                out.getOrPut(rs.getString(1)) { mutableListOf() }.add(rs.getString(2))
            }
        }
        out
    }

    /**
     * The declared primary key is the one the database enforces.
     *
     * **This was added because a drift of exactly this shape had been sitting in `Tables.kt`.** The
     * abstract `CourseExerciseException` base declared `PrimaryKey(courseExercise)`, so both exception
     * tables claimed a single-column key while changesets `190724-1` and `300724-1` give each of them a
     * composite `(course_exercise_id, student_id)` / `(course_exercise_id, group_id)`. The three checks
     * above could not see it: every column existed, with matching nullability, in both directions.
     *
     * What it costs is not nothing. `upsert` derives its `ON CONFLICT` target from the declared key
     * unless the caller passes one, so an upsert written the short way would have conflicted on the
     * course exercise alone and overwritten a different student's exception. Both existing call sites
     * in `PutCourseExerciseExceptions` pass the key columns explicitly and were therefore correct — but
     * they were correct by the author's care rather than by the model, and the next one would not be.
     * `SchemaUtils.create` would also build the wrong constraint, which is why this check has to read
     * the live schema rather than compare the model to itself.
     *
     * Order matters and is compared: a composite key's column order decides which prefix lookups the
     * backing index can serve, so `(a, b)` and `(b, a)` are not the same declaration.
     */
    /**
     * Key drifts that need a *migration* to close rather than a `Tables.kt` edit, each with why.
     *
     * Shrink-only, like [knownNullabilityDrift]: an entry that no longer disagrees fails the test
     * below, so closing one forces deleting its line in the same commit.
     */
    private val knownPrimaryKeyDrift = mapOf(
        // The database's key is (id, user_id); Exposed's LongIdTable says (id), and Exposed is the one
        // describing the intent. `id` is a bigserial surrogate, so user_id in the key adds nothing but
        // does mean the constraint never asserts that `id` alone is unique.
        //
        // Declaring the wider key here would be the wrong direction of fix — it would write the
        // accident into the model. Narrowing the database is a changeset against a table that holds
        // production rows, which is a decision, not a side effect of adding this check. Safe to leave:
        // nothing upserts LogReport (insert, select and deleteWhere only), and a sequence does not
        // hand out the same id twice, so the missing uniqueness is unreachable in practice.
        "log_report" to "DB key is (id, user_id); the extra column is a 2019 changeset accident, and " +
                "narrowing it is a migration decision. Nothing upserts this table.",
    )

    @Test
    fun `every Exposed table declares the primary key the database enforces`() {
        val live = livePrimaryKeys()

        val drifted = ExposedTables.all().mapNotNull { table ->
            val declared = table.primaryKey?.columns?.map { it.name.lowercase() } ?: return@mapNotNull null
            val actual = live[table.tableName.lowercase()]?.map { it.lowercase() } ?: return@mapNotNull null
            if (declared == actual) null
            else table.tableName.lowercase() to
                    "${table.tableName}: Exposed says (${declared.joinToString(", ")}), " +
                    "database says (${actual.joinToString(", ")})"
        }.toMap()

        val unexplained = (drifted.keys - knownPrimaryKeyDrift.keys).sorted().map { drifted.getValue(it) }
        assertTrue(unexplained.isEmpty()) {
            "These tables declare a primary key the database does not have:\n" +
                    unexplained.joinToString("\n") { "  $it" } +
                    "\n\nA wrong key is not cosmetic. `upsert` derives its ON CONFLICT target from the " +
                    "declared key, so one that is too narrow overwrites a row that should have been " +
                    "inserted; column order decides which prefix lookups the backing index can serve; " +
                    "and SchemaUtils would build the constraint wrong. Fix the declaration, or record " +
                    "it in knownPrimaryKeyDrift with the reason it needs a migration instead."
        }

        val stale = (knownPrimaryKeyDrift.keys - drifted.keys).sorted()
        assertTrue(stale.isEmpty()) {
            "These entries in knownPrimaryKeyDrift no longer disagree with the database:\n" +
                    stale.joinToString("\n") { "  $it" } +
                    "\n\nDelete the entry, so the list keeps meaning what it says."
        }

        val unjustified = knownPrimaryKeyDrift.filterValues { it.isBlank() }.keys.sorted()
        assertTrue(unjustified.isEmpty()) {
            "These knownPrimaryKeyDrift entries have no reason recorded: $unjustified"
        }
    }
}
