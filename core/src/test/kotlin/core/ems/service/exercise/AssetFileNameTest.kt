package core.ems.service.exercise

import core.db.GraderType
import core.db.SolutionFileType
import jakarta.validation.Validation
import jakarta.validation.Validator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * That an exercise asset's file name is validated, and — the part that was actually broken — that the
 * validation runs at all.
 *
 * `@Valid` on a controller parameter validates that class's own fields and stops. It does not descend
 * into a collection unless the collection property is itself annotated `@field:Valid`. Neither
 * `Req.assets` was, so the `@field:Size(max = 100)` that has sat on `file_name` since the DTO was
 * written **had never been evaluated once**. It read as a constraint, it was cited as a constraint in
 * a review of this endpoint, and it was decoration.
 *
 * So these tests validate the **enclosing request**, not `ReqAsset` on its own. Validating `ReqAsset`
 * directly would pass with or without `@field:Valid` and would prove nothing about the thing that was
 * wrong.
 *
 * Context-free: a `Validator` built here rather than taken from the Spring context, so this runs in CI
 * without a database. The same rule is enforced again in the executor, in `_checked_asset_name` —
 * that is the copy that protects the grading host, since it is the code that opens the file. This one
 * is so that the teacher who typed the name hears about it while saving the exercise, rather than
 * every submission to it failing later for reasons visible only in an executor log.
 */
class AssetFileNameTest {

    private val validator: Validator = Validation.buildDefaultValidatorFactory().validator

    private fun createReqWith(vararg assetNames: String) = CreateExercise.Req(
        parentDirIdStr = null,
        title = "Sum of two numbers",
        textMd = "Add them.",
        public = false,
        anonymousAutoassessEnabled = false,
        graderType = GraderType.AUTO,
        solutionFileName = "lahendus.py",
        solutionFileType = SolutionFileType.TEXT_EDITOR,
        gradingScript = "#!/bin/sh\ntrue\n",
        containerImage = "python:3.12",
        maxTime = 10,
        maxMem = 64,
        assets = assetNames.map { CreateExercise.ReqAsset(it, "content") },
    )

    private fun updateReqWith(vararg assetNames: String) = UpdateExercise.Req(
        title = "Sum of two numbers",
        textMd = "Add them.",
        graderType = GraderType.AUTO,
        solutionFileName = "lahendus.py",
        solutionFileType = SolutionFileType.TEXT_EDITOR,
        gradingScript = "#!/bin/sh\ntrue\n",
        containerImage = "python:3.12",
        maxTime = 10,
        maxMem = 64,
        assets = assetNames.map { UpdateExercise.ReqAsset(it, "content") },
    )

    /** Control characters make a useless test name, so they are escaped for display. */
    private fun display(raw: String): String =
        if (raw.isEmpty()) "(empty)"
        else raw.map { if (it.code < 0x20) "\\u%04x".format(it.code) else it.toString() }.joinToString("")

    /** Names that must be refused, each here for a stated reason rather than as an arbitrary sample. */
    @TestFactory
    fun `an asset name that is not a plain file name is refused`() = listOf(
        // One level up from the submission directory is the Docker build context, where the Dockerfile
        // the grading image is built from lives — and assets are written after it.
        "../Dockerfile",
        "../evaluate.sh",
        "../../escaped.txt",
        // os.path.join discards its base in front of an absolute path, so this does not even have to
        // walk anywhere to leave the directory.
        "/tmp/escaped.txt",
        // Not a separator on POSIX, which is what makes a basename-only check insufficient.
        """..\..\escaped.txt""",
        "sub/dir.txt",
        "..",
        ".",
        "",
        // Control characters, which a NUL is only the loudest of: `open` raises on that one by itself,
        // while a tab or a newline goes through and ends up in log lines and in the tar stream Docker
        // builds the context from. The pattern excludes the whole range, so these two stand for it.
        "with\nnewline",
        "with\ttab",
    ).map { name ->
        DynamicTest.dynamicTest(display(name)) {
            assertTrue(validator.validate(createReqWith(name)).isNotEmpty()) {
                "create accepted asset name ${display(name)}"
            }
            assertTrue(validator.validate(updateReqWith(name)).isNotEmpty()) {
                "update accepted asset name ${display(name)}"
            }
        }
    }

    @TestFactory
    fun `an ordinary asset name is accepted`() = listOf(
        "input.txt",
        "helper.py",
        // Collides with the file the student's submission is written to, and that is a documented
        // feature — an exercise may supply the program under test. Pinned here so that tightening the
        // rule to "no collisions" has to be a decision rather than a side effect.
        "lahendus.py",
        "submission.py",
        "tsl_spec.json",
        "andmed-2024.csv",
        "õäöü.txt",
        ".hidden",
        "..leading-dots.txt",
    ).map { name ->
        DynamicTest.dynamicTest(name) {
            assertEquals(emptySet<Any>(), validator.validate(createReqWith(name))) { "create refused $name" }
            assertEquals(emptySet<Any>(), validator.validate(updateReqWith(name))) { "update refused $name" }
        }
    }

    @Test
    fun `one bad name among good ones is still caught`() {
        // Really a test that a valid first element does not satisfy the check for the rest of them.
        assertTrue(validator.validate(createReqWith("input.txt", "helper.py", "../Dockerfile")).isNotEmpty())
    }

    @Test
    fun `the length limit is now reachable, which it was not before`() {
        // Not a new constraint — `@field:Size(max = 100)` was always written on this field. It simply
        // never ran, so this assertion would have failed before `@field:Valid` was added, on a DTO
        // that looked fully validated.
        assertTrue(validator.validate(createReqWith("a".repeat(101))).isNotEmpty())
        assertEquals(emptySet<Any>(), validator.validate(createReqWith("a".repeat(100))))
    }
}
