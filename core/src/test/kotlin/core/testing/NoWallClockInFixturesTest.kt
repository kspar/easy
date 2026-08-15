package core.testing

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Fails if any test source calls `DateTime.now()`.
 *
 * This is the guard for the defect behind EZ-1763. Two fixture submissions were inserted with
 * `DateTime.now()` back to back, landed in the same millisecond, and made "the latest submission" a
 * coin toss between grade 71 and grade 81 — measured at 4 failures in 5 consecutive runs, on code
 * that had nothing to do with them. Nobody noticed for months, because CI had never run those tests.
 *
 * [TestClock] is the replacement: an explicit timeline where "and then, a minute later" is written
 * down rather than hoped for. A test that needs two rows to share an instant asks for that
 * deliberately, with `TestClock.fixed(n)`.
 *
 * A file-reading test rather than a lint rule because it needs no tooling and no configuration, and
 * this repo already prefers that trade. If it ever needs an exception, add the file to
 * [ALLOWED] with the reason — the point is that the decision is visible, not that it is impossible.
 */
class NoWallClockInFixturesTest {

    private val allowed = setOf(
        // This file. It has to name the thing it forbids, in prose and in the matcher below.
        "core/testing/NoWallClockInFixturesTest.kt",
    )

    /**
     * Comment lines do not count. Both [Fixtures] and this test explain *why* the wall clock is
     * banned, which means writing it down — and a guard that fires on its own rationale is a guard
     * people delete. Line comments, block-comment openers and KDoc continuation lines all qualify.
     */
    private fun isComment(line: String): Boolean =
        line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    @Test
    fun `test sources use TestClock rather than the wall clock`() {
        val root = File("src/test/kotlin")
        assertTrue(root.isDirectory) {
            "Expected to find test sources at ${root.absolutePath} — this test reads them from disk, " +
                    "so it depends on the working directory being the core module."
        }

        val scanned = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.relativeTo(root).invariantSeparatorsPath !in allowed }
            .toList()

        // A scan that reaches nothing passes, and looks exactly like a clean run. Same reasoning as
        // the table-count check in ExposedTables.
        assertTrue(scanned.size >= 5) {
            "Only ${scanned.size} test sources scanned from ${root.absolutePath} — the walk is broken."
        }

        val offenders = scanned
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) -> "DateTime.now()" in line && !isComment(line) }
                    .map { (i, line) -> "${file.relativeTo(root).invariantSeparatorsPath}:${i + 1}  ${line.trim()}" }
            }

        assertTrue(offenders.isEmpty()) {
            "These test sources call DateTime.now():\n" +
                    offenders.joinToString("\n") { "  $it" } +
                    "\n\nUse core.testing.TestClock instead. Two rows written with the wall clock can " +
                    "share a millisecond, and a test that depends on which one won is a test that " +
                    "fails a few runs in five (EZ-1763). If a test is genuinely about wall-clock " +
                    "behaviour, add it to the allowed set in this test with a reason."
        }
    }
}
