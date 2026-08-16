package com.example.demo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import tsl.common.model.CheckTypeLong
import tsl.common.model.ContainsTest
import tsl.common.model.ContainsWhat
import tsl.common.model.GenericCheckLong
import tsl.common.model.Scope
import tsl.common.model.TSL
import tsl.common.model.TSLFormat
import java.util.concurrent.TimeUnit

/**
 * **The generated script has to be Python.**
 *
 * This compiler's output is not data — it is source code, executed inside a container against a
 * student's submission. Every string in a TSL spec that reaches it (a test name, a failure message,
 * an expected value, a filename) is written by a teacher, and teachers write apostrophes.
 *
 * That makes this the injection-shaped bug class, and it fails in the worst possible place: the
 * generated script is only ever run *during grading*, so a spec that compiles happily and produces
 * a `SyntaxError` looks to a teacher like the executor is broken and to a student like their own
 * submission is. Nothing between here and there parses the output — which is how `PyStr` came to
 * have three separate ways of emitting a literal Python cannot close.
 *
 * The oracle is CPython itself, via `python3 -c "import ast; ast.parse(...)"`. Nothing else is an
 * authority on what Python accepts, and a hand-rolled check would be a second, worse implementation
 * of the thing being tested.
 *
 * ### Two known gaps, deliberately not asserted here
 *
 * Both are recorded in `doc/testing-log.md` with their measured blast radius, because fixing either
 * changes what live exercises grade — which is a decision, not a bug fix:
 *
 * 1. **A value beginning with `"` is emitted raw**, so `"unterminated` is a `SyntaxError` and
 *    `", __import__('os').system('id'), "` is arbitrary code in the grading script. 178 values
 *    across 41 of the 720 exercises in the migration corpus start with `"` and would change meaning
 *    if this were removed.
 * 2. **Backslashes inside a value stay Python escapes.** `\n` in a spec becomes a real newline in
 *    the generated literal, and 18 exercises depend on exactly that. So `path\to\file` does *not*
 *    survive the round trip, and asserting that it should would be asserting against the format.
 *
 * What is asserted below is everything else — and everything else used to be broken too.
 */
class PythonSyntaxTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("hostileStrings")
    @DisplayName("a teacher's punctuation survives being put in a test name")
    fun hostileNameIsValidPython(label: String, hostile: String) {
        assumePython()
        assertParses(compile(spec(testName = hostile)), "test name $label")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hostileStrings")
    @DisplayName("and in a failure message")
    fun hostileMessageIsValidPython(label: String, hostile: String) {
        assumePython()
        assertParses(compile(spec(failedMessage = hostile)), "failed message $label")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hostileStrings")
    @DisplayName("and in an expected value")
    fun hostileExpectedValueIsValidPython(label: String, hostile: String) {
        assumePython()
        assertParses(compile(spec(expectedValue = hostile)), "expected value $label")
    }

    /**
     * The two shapes that used to close the literal early — and silently.
     *
     * A value ending in `'` produced four consecutive quotes; Python closed at the third and treated
     * the rest as an adjacent literal, so `1 4 7 ''` arrived at the student as `1 4 7 `. Two
     * exercises in the corpus were losing characters that way, and nothing anywhere would have shown
     * it: the script ran, the check ran, and it compared against the wrong string.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("literalTerminators")
    @DisplayName("a value that used to close the literal early now arrives intact")
    fun terminatorRoundTrips(label: String, hostile: String) {
        assumePython()
        assertEquals(hostile, nameReadBackByPython(compile(spec(testName = hostile)))) {
            "The value changed on the way through ($label)"
        }
    }

    /**
     * The keys of a check dictionary are plain names.
     *
     * `PyDict` quotes keys itself. Three callers also pre-quoted theirs, so between 2026-08-07 and
     * this test every check dict was emitted with keys like `'check_type'` — apostrophes included —
     * which no lookup in tiivad can ever match. 635 of the 720 exercises in the migration corpus
     * were affected.
     *
     * Asserted by evaluating the generated dict in Python and reading its keys back, rather than by
     * matching text: the text form is exactly what everyone looked at for nine days without seeing
     * it.
     */
    @Test
    @DisplayName("check dictionary keys are bare names, not quoted strings")
    fun checkDictKeysAreBareNames() {
        assumePython()
        val generated = compile(spec(testName = "keys"))

        val keys = python(
            """
            import ast, sys
            tree = ast.parse(sys.stdin.read())
            for node in ast.walk(tree):
                if isinstance(node, ast.Dict):
                    sys.stdout.write(','.join(sorted(ast.literal_eval(k) for k in node.keys)))
                    sys.exit(0)
            sys.exit(3)
            """.trimIndent(),
            generated,
        )
        assertEquals(0, keys.exitCode) { "No dict in the generated script: ${keys.stderr}\n$generated" }

        val found = keys.stdout.split(",")
        assertTrue(found.contains("check_type")) { "Keys were $found" }
        assertTrue(found.none { it.startsWith("'") || it.endsWith("'") }) {
            "A key still carries its own quotes: $found"
        }
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun compile(spec: String): String =
        compileTSL(spec, "1.0.0", "tiivad", TSLSpecFormat.JSON).generatedScripts.single()

    private fun assertParses(generated: String, what: String) {
        val result = python("import ast, sys; ast.parse(sys.stdin.read())", generated)
        assertTrue(result.exitCode == 0) {
            "The generated Python for $what does not parse.\n${result.stderr}\n" +
                    "--- generated ---\n${generated.take(3000)}"
        }
    }

    /** Have Python parse the script and hand back the `name=` it read, so the oracle is CPython. */
    private fun nameReadBackByPython(generated: String): String {
        val read = python(
            """
            import ast, sys
            for node in ast.walk(ast.parse(sys.stdin.read())):
                if isinstance(node, ast.Call) and getattr(node.func, 'id', None) == 'execute_test':
                    for kw in node.keywords:
                        if kw.arg == 'name':
                            sys.stdout.write(ast.literal_eval(kw.value))
                            sys.exit(0)
            sys.exit(3)
            """.trimIndent(),
            generated,
        )
        assertTrue(read.exitCode == 0) { "Could not read the name back: ${read.stderr}\n$generated" }
        return read.stdout
    }

    private data class Ran(val exitCode: Int, val stdout: String, val stderr: String)

    private fun python(script: String, stdin: String): Ran {
        val process = ProcessBuilder(pythonBin, "-c", script).start()
        process.outputStream.use { it.write(stdin.toByteArray()) }
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        // Generous: this is a parse, not a program. A timeout means something is very wrong.
        assertTrue(process.waitFor(60, TimeUnit.SECONDS)) { "$pythonBin did not finish" }
        return Ran(process.exitValue(), out, err)
    }

    private fun assumePython() = assumeTrue(pythonAvailable) {
        "No usable '$pythonBin' on PATH, so CPython could not be asked whether this is Python"
    }

    companion object {
        private val pythonBin: String = System.getProperty("tsl.python") ?: "python3"

        /**
         * Whether `python3` is there — checked once.
         *
         * A skip and not a failure, because the JVM build has to stay runnable on a machine with no
         * Python. It is a *named* skip rather than a silent omission: this is the only thing in the
         * module that can tell valid Python from a plausible-looking string, so a run without it is
         * materially weaker and should say so.
         */
        private val pythonAvailable: Boolean = runCatching {
            val p = ProcessBuilder(pythonBin, "-c", "import ast").redirectErrorStream(true).start()
            p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0
        }.getOrDefault(false)

        /**
         * Strings a teacher types, and a few an attacker would.
         *
         * Written with Kotlin escapes rather than pasted characters. Pasting is how an invisible
         * control byte got into this list the first time round, which then failed as "source code
         * string cannot contain null bytes" and read exactly like a compiler bug.
         */
        @JvmStatic
        fun hostileStrings(): List<Array<String>> = listOf(
            arrayOf("apostrophe in Estonian", "Kontrollib, kas programm töötab"),
            arrayOf("single quote", "the student's answer"),
            arrayOf("double quote inside", "say \"hello\" politely"),
            arrayOf("triple single quote", "ends a literal: ''' and continues"),
            arrayOf("trailing single quote", "ends with a quote'"),
            arrayOf("two trailing quotes", "1 4 7 ''"),
            arrayOf("only quotes", "''"),
            arrayOf("trailing backslash", "ends with a backslash\\"),
            arrayOf("two trailing backslashes", "ends with two\\\\"),
            arrayOf("newline", "first line\nsecond line"),
            arrayOf("tab", "before\tafter"),
            arrayOf("non-ascii", "õäöü ŠŽ — ✓"),
            arrayOf("emoji", "well done 🎉"),
            arrayOf("python code, quoted out", "', __import__('os').system('id'), '"),
            arrayOf("brace and percent", "100% of {tests} passed"),
            arrayOf("comment marker", "# not a comment"),
            arrayOf("only whitespace", "   "),
            arrayOf("empty", ""),
        )

        /** The subset whose *value* must survive, not merely parse. */
        @JvmStatic
        fun literalTerminators(): List<Array<String>> = listOf(
            arrayOf("trailing single quote", "ends with a quote'"),
            arrayOf("two trailing quotes", "1 4 7 ''"),
            arrayOf("only quotes", "''"),
            arrayOf("python code, quoted out", "', __import__('os').system('id'), '"),
            arrayOf("apostrophe in Estonian", "Kontrollib, kas programm töötab"),
            arrayOf("non-ascii", "õäöü ŠŽ — ✓"),
            arrayOf("emoji", "well done 🎉"),
        )

        /** A minimal but complete spec with one contains-test, for varying one field at a time. */
        private fun spec(
            testName: String = "A test",
            failedMessage: String = "failed",
            expectedValue: String = "42",
        ): String = TSLFormat.encodeToString(
            TSL.serializer(),
            TSL(
                validateFiles = false,
                requiredFiles = listOf("lahendus.py"),
                tslVersion = "1.0.0",
                tests = listOf(
                    ContainsTest(
                        id = 1,
                        scope = Scope.PROGRAM,
                        containsWhat = ContainsWhat.KEYWORD_NO_ARG,
                        containsWhatArg = null,
                        functionName = null,
                        className = null,
                        genericCheck = GenericCheckLong(
                            checkType = CheckTypeLong.ALL_OF_THESE,
                            expectedValue = listOf(expectedValue),
                            beforeMessage = "checking",
                            passedMessage = "passed",
                            failedMessage = failedMessage,
                        ),
                    ).also { it.name = testName },
                ),
            ),
        )
    }
}
