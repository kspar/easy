package com.example.demo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File

/**
 * The Python the compiler produces, committed, so that changing it is a diff somebody reads.
 *
 * ### Why this and not more assertions
 *
 * The emitter's output is a large string built by a dozen classes. Asserting on it property by
 * property means writing down, in advance, which properties matter — and the defects this compiler
 * has actually shipped were never on that list. EZ-1774 is the case in point: `PyDict` learned to
 * quote its own keys, three callers were already quoting theirs, and every check dictionary got keys
 * like `'\'check_type\''`. That is **valid Python**, so `PythonSyntaxTest` cannot see it; it is not a
 * crash, so nothing downstream reports it; and it sat on master for nine days.
 *
 * What it *is*, is a one-character change on thousands of lines of generated output. A golden file
 * turns that into a diff on the pull request that introduced it. Same argument as
 * `doc/core/api-shapes.json`, one directory over: **the diff is the review artefact.**
 *
 * ### One spec per test type, deliberately
 *
 * `Compiler.generateAssessmentCode` is a `when` over the sealed `Test` hierarchy — a branch per
 * type — and a branch with no golden file is a branch whose output nobody has ever looked at. The
 * cases are weighted by what teachers actually use, counted from the migration corpus; see the
 * README beside the files.
 *
 * `escaping.json` is not a test type. It is every route a teacher's punctuation takes into a Python
 * string literal, in one file, so that a proposed change to `PyStr` has one obvious place to be
 * reviewed against.
 *
 * ### Regenerating
 *
 *     ./gradlew :tsl:test --tests '*GoldenOutputTest*' -Ptsl.golden.update=true
 *
 * Then **read the diff**. A golden file blessed without reading it records the defect as the
 * expectation and makes every subsequent diff clean — which is worse than having no golden file,
 * because it looks like coverage. The flag exists to be used once per deliberate change.
 */
class GoldenOutputTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("goldenSpecs")
    @DisplayName("the generated Python matches what is committed")
    fun matchesGolden(name: String, spec: File) {
        val expectedFile = File(spec.parentFile, "$name.py.expected")
        val actual = compileTSL(spec.readText(), "1", "tiivad", TSLSpecFormat.JSON)
            .generatedScripts
            .single()

        if (updating) {
            expectedFile.writeText(actual)
            return
        }

        assertTrue(expectedFile.isFile) {
            "No golden file for '$name'. Generate it with:\n" +
                    "  ./gradlew :tsl:test --tests '*GoldenOutputTest*' -Ptsl.golden.update=true\n" +
                    "then read the diff before committing it."
        }
        assertEquals(expectedFile.readText(), actual) {
            "The generated Python for '$name' changed.\n\n" +
                    "If that was the point, regenerate and **read the diff**:\n" +
                    "  ./gradlew :tsl:test --tests '*GoldenOutputTest*' -Ptsl.golden.update=true\n" +
                    "  git diff tsl/src/test/resources/golden\n\n" +
                    "If it was not, something in the emitter changed that nobody asked for."
        }
    }

    /**
     * Every supported test type has a golden file.
     *
     * Coverage by construction, the shape this repo already uses for endpoint samples: adding a
     * type to the sealed hierarchy fails the build until somebody has looked at what it compiles to.
     * Without it the set of golden files silently becomes "whatever existed when this was written".
     */
    @Test
    @DisplayName("every test type in the model has a golden file")
    fun everyTypeIsCovered() {
        // Read the types out of the specs rather than inferring them from file names. A convention
        // that `contains.json` covers `contains_test` is one rename away from being silently false,
        // and the failure would be this guard quietly covering nothing.
        val covered = goldenSpecs()
            .flatMapTo(mutableSetOf()) { (it[1] as File).readText().let(::testTypesIn) }

        val missing = MODEL_TEST_TYPES - covered
        assertTrue(missing.isEmpty()) {
            "These test types have no golden spec, so nothing has ever looked at what they compile " +
                    "to:\n" + missing.joinToString("\n") { "  $it" } +
                    "\n\nAdd a spec under tsl/src/test/resources/golden and generate its .py.expected."
        }

        val unknown = covered - MODEL_TEST_TYPES.toSet()
        assertTrue(unknown.isEmpty()) {
            "These golden specs use test types the model does not have:\n" +
                    unknown.joinToString("\n") { "  $it" } +
                    "\n\nEither the model lost a type and the spec is stale, or MODEL_TEST_TYPES is."
        }
    }

    companion object {
        private val updating: Boolean = System.getProperty("tsl.golden.update") == "true"

        private val DIR = File("src/test/resources/golden")

        /**
         * The `@SerialName` of every concrete `Test` in the model.
         *
         * Hardcoded rather than reflected: `tsl-common` is a dependency of this module, and walking
         * a sealed hierarchy for its serial names needs `kotlin-reflect` plus knowledge of how
         * kotlinx.serialization stores them. A list that must be edited alongside the model is
         * honest here — the test above is what makes forgetting it fail.
         */
        private val TYPE_FIELD = Regex(""""type"\s*:\s*"([a-z_]+)"""")

        private fun testTypesIn(specJson: String): Set<String> =
            TYPE_FIELD.findAll(specJson).map { it.groupValues[1] }.toSet()

        private val MODEL_TEST_TYPES = listOf(
            "program_execution_test",
            "function_execution_test",
            "definition_test",
            "calls_test",
            "contains_test",
            "class_instance_test",
            "function_is_test",
            "placeholder_test",
        )

        @JvmStatic
        fun goldenSpecs(): List<Array<Any>> {
            val specs = DIR.listFiles { f: File -> f.extension == "json" }
                ?.sortedBy { it.name }
                .orEmpty()

            // A parameterised test whose source returns nothing passes, and reads exactly like a
            // clean run. Same reasoning as the fail-on-zero guard in the root build.
            check(specs.size >= 8) {
                "Only ${specs.size} golden specs found in ${DIR.absolutePath} — the files are the " +
                        "point of this test, so an empty scan is a broken test rather than a pass."
            }
            return specs.map { arrayOf(it.nameWithoutExtension, it) }
        }
    }
}
