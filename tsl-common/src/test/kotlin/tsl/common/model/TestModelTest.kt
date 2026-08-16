package tsl.common.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
// Aliased: this file lives in the model's own package, where `Test` already means
// `tsl.common.model.Test`. Importing JUnit's unaliased shadows it, and the compiler then reports
// eleven unrelated-looking errors about `copyTest` and `serializer` not existing.
import org.junit.jupiter.api.Test as JUnitTest

/**
 * The TSL model itself — the thing `:core`, `:tsl` and the web editor all agree about.
 *
 * This module had no tests at all, which is worth stating plainly given what it is: the shared
 * vocabulary between a Kotlin backend, a Kotlin compiler and a TypeScript form builder, serialised
 * as JSON and stored in a database. Every one of those four can disagree with the others silently.
 *
 * The properties here are the ones where being wrong produces no error anywhere — a test that
 * quietly loses its name, an id that changes when it should not, a spec that round-trips into
 * something subtly different from what a teacher saved.
 */
class TestModelTest {

    /**
     * A spec survives being written and read back.
     *
     * This is the operation the editor performs on every save and `UpdateExercise` performs on every
     * compile. A field that serialises but does not deserialise, or deserialises to a different
     * default, is data loss with no exception — and `TSLFormat` sets `encodeDefaults = true`
     * precisely so that what is stored is explicit rather than implied.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("everyTestType")
    @DisplayName("a test round-trips through JSON unchanged")
    fun roundTrips(name: String, test: Test) {
        val tsl = TSL(
            validateFiles = true,
            requiredFiles = listOf("lahendus.py"),
            tslVersion = "1.0.0",
            tests = listOf(test),
        )

        val json = TSLFormat.encodeToString(tsl)
        val back = TSLFormat.decodeFromString<TSL>(json)

        assertEquals(tsl, back) { "Round-trip changed the spec.\n$json" }
        // And a second pass is byte-identical, so nothing is normalised on the way through. Without
        // this, a field that serialises differently than it deserialises shows up as a spurious
        // "modified" every time an exercise is opened and saved.
        assertEquals(json, TSLFormat.encodeToString(back))
    }

    /**
     * `copyTest(newId)` changes the id and **nothing else**.
     *
     * It is what "duplicate this test" calls in the editor. A copy that also reset a message, or
     * dropped a check, would produce a test that looks right in the list and grades differently —
     * and the teacher who duplicated it has no reason to re-read every field.
     *
     * Asserted against the **original**, not against another copy. The first version of this
     * compared `test.copyTest(1)` with `copy.copyTest(1)` — both sides through `copyTest`, so a
     * `copyTest` that cleared a field cleared it on both and the assertion could not fail. Confirmed
     * by making `ContainsTest.copyTest` drop `containsWhatArg` and watching it stay green.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("everyTestType")
    @DisplayName("copyTest changes the id and nothing else")
    fun copyTestChangesOnlyTheId(name: String, test: Test) {
        val copy = test.copyTest(9999)

        assertEquals(9999L, copy.id)
        assertEquals(test::class, copy::class) { "copyTest returned a different type" }

        // Copying with the id it already has must give back an equal object. One side goes through
        // copyTest and the other does not, which is what makes this able to fail.
        assertEquals(test, test.copyTest(test.id)) { "copyTest changed something other than the id" }

        // And the copy differs from the original in the id alone.
        assertEquals(
            TSLFormat.encodeToString(Test.serializer(), test),
            TSLFormat.encodeToString(Test.serializer(), copy).replace("\"id\": 9999", "\"id\": ${test.id}"),
        ) { "copyTest changed something other than the id" }
    }

    /**
     * Every type has a default name, and it is not blank.
     *
     * `PyExecuteTest` emits `name = test.name ?: test.getDefaultName()`, so a blank default reaches
     * a student as a test called nothing. Estonian, because that is what these are.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("everyTestType")
    @DisplayName("the default name is not blank")
    fun defaultNameIsNotBlank(name: String, test: Test) {
        assertTrue(test.getDefaultName().isNotBlank()) { "$name has a blank default name" }
    }

    /**
     * Coverage by construction: every concrete `Test` in the sealed hierarchy is in the list above.
     *
     * Adding a type to the model without adding it here would otherwise leave it round-tripping,
     * copying and naming itself untested — and the first sign would be a teacher's spec coming back
     * from a save with a field missing.
     */
    @JUnitTest
    @DisplayName("every concrete test type in the model is exercised")
    fun everyTypeIsExercised() {
        val inModel = Test::class.sealedSubclasses
            .filterNot { it.isAbstract }
            .map { it.simpleName!! }
            .toSet()
        val exercised = everyTestType().map { (it[1] as Test)::class.simpleName!! }.toSet()

        assertEquals(emptySet<String>(), inModel - exercised) {
            "These test types are in the sealed hierarchy but not exercised by this file"
        }
        assertTrue(inModel.size >= 8) { "Only ${inModel.size} subtypes found — the reflection is broken" }
    }

    // --- the defaults that mean something --------------------------------------------------------

    /**
     * An absent `propertyCheck.mustHaveProperty` is **true**.
     *
     * It reaches tiivad as `expected_value`, so reading an absent one as false inverts the meaning
     * of every `function_is_test` a teacher never touched: "is recursive" silently becomes "is not
     * recursive", and the students who did it right fail.
     */
    @JUnitTest
    fun `an absent mustHaveProperty is true`() {
        val decoded = TSLFormat.decodeFromString<FunctionPropertyCheck>(
            """{"beforeMessage":"a","passedMessage":"b","failedMessage":"c"}"""
        )
        assertTrue(decoded.mustHaveProperty)
    }

    @JUnitTest
    fun `an absent points weight is one, and an explicit zero survives`() {
        // 0 is a real choice — a test that runs but does not count. It is emitted as a Python float
        // via PyFloat, so a falsy check anywhere on the way would erase the distinction.
        val default = TSLFormat.decodeFromString<Test>(
            """{"type":"placeholder_test","id":1}"""
        )
        assertEquals(1.0, default.pointsWeight)
    }

    @JUnitTest
    fun `an absent visibleToUser is true`() {
        // Hidden tests are the exception, so the default has to be visible — a spec that omits the
        // field must not hide the test from the student.
        val decoded = TSLFormat.decodeFromString<Test>("""{"type":"placeholder_test","id":1}""")
        assertTrue(decoded.visibleToUser)
    }

    /**
     * A name is nullable, and null is not the same as empty.
     *
     * `PyExecuteTest` branches on `test.name != null` to decide whether to use the default. An
     * empty string is not null, so it takes the other branch and the student sees a test with no
     * name at all — which is why the editor's "clear the box" has to remove the key rather than
     * write `""`.
     */
    @JUnitTest
    fun `a null name and an empty name are different`() {
        val json = """{"type":"placeholder_test","id":1,"name":""}"""
        assertEquals("", TSLFormat.decodeFromString<Test>(json).name)
        assertNull(TSLFormat.decodeFromString<Test>("""{"type":"placeholder_test","id":1}""").name)
    }

    /** An unknown type is refused rather than silently dropped. */
    @JUnitTest
    fun `a test type the model does not have fails to decode`() {
        val thrown = runCatching {
            TSLFormat.decodeFromString<Test>("""{"type":"program_imports_module_test","id":1}""")
        }.exceptionOrNull()

        assertNotNull(thrown) { "An unknown test type decoded successfully" }
        assertTrue(thrown!!.message!!.contains("program_imports_module_test")) { thrown.message!! }
    }

    companion object {
        /**
         * One populated instance of every concrete test type.
         *
         * Populated rather than minimal on purpose: a round-trip of all-defaults passes for a model
         * that silently drops half its fields.
         */
        @JvmStatic
        fun everyTestType(): List<Array<Any>> {
            val longCheck = GenericCheckLong(
                checkType = CheckTypeLong.ALL_OF_THESE,
                nothingElse = true,
                expectedValue = listOf("a", "b"),
                dataCategory = DataCategory.CONTAINS_STRINGS,
                ignoreCase = true,
                beforeMessage = "before",
                passedMessage = "passed",
                failedMessage = "failed",
            )
            val check = GenericCheck(
                id = 7,
                checkType = CheckType.ANY_OF_THESE,
                nothingElse = false,
                expectedValue = listOf("x"),
                elementsOrdered = true,
                dataCategory = DataCategory.CONTAINS_NUMBERS,
                outputCategory = OutputCategory.LAST_OUTPUT,
                ignoreCase = false,
                beforeMessage = "before",
                passedMessage = "passed",
                failedMessage = "failed",
            )
            val fileCheck = OutputFileCheck(
                fileName = "out.txt",
                checkType = CheckType.ALL_OF_THESE,
                nothingElse = true,
                expectedValue = listOf("1"),
                elementsOrdered = false,
                dataCategory = DataCategory.EQUALS,
                ignoreCase = null,
                beforeMessage = "before",
                passedMessage = "passed",
                failedMessage = "failed",
            )

            return listOf<Test>(
                PlaceholderTest(id = 1),
                ContainsTest(
                    id = 2, scope = Scope.FUNCTION, containsWhat = ContainsWhat.KEYWORD_WITH_PRECEDING_ARG,
                    containsWhatArg = "import", functionName = "f", className = "C", genericCheck = longCheck,
                ),
                CallsTest(
                    id = 3, scope = Scope.CLASS, targetType = TargetType.CLASS_FUNCTION,
                    functionName = "f", className = "C", targetClassName = "D", genericCheck = longCheck,
                ),
                DefinitionTest(
                    id = 4, scopeType = Scope.PROGRAM, className = "C", functionName = "f",
                    definitionCheckValue = "Koer", superClassName = "Loom",
                    definitionCheckType = DefinitionCheckType.CLASS, genericCheck = longCheck,
                ),
                FunctionIsTest(
                    id = 5, functionName = "f", functionProperty = FunctionProperty.PURE,
                    propertyCheck = FunctionPropertyCheck(
                        mustHaveProperty = false,
                        beforeMessage = "before", passedMessage = "passed", failedMessage = "failed",
                    ),
                ),
                FunctionExecutionTest(
                    id = 6, functionName = "liida", functionType = FunctionType.METHOD,
                    createObject = "o = C()\nreturn o", arguments = listOf("1", "2"),
                    standardInputData = listOf("in"), inputFiles = listOf(FileData("a.txt", "content")),
                    genericChecks = listOf(check),
                    returnValueCheck = ReturnValueCheck("3", "before", "passed", "failed"),
                    paramValueChecks = listOf(ParamValueCheck(0, "1", "before", "passed", "failed")),
                    outputFileChecks = listOf(fileCheck),
                ),
                ProgramExecutionTest(
                    id = 7, standardInputData = listOf("a"), inputFiles = listOf(FileData("f", "c")),
                    genericChecks = listOf(check), outputFileChecks = listOf(fileCheck),
                    exceptionCheck = ExceptionCheck(true, "before", "passed", "failed"),
                ),
                ClassInstanceTest(
                    id = 8, className = "KosmoseLaev", createObject = "return KosmoseLaev()",
                    classInstanceChecks = listOf(
                        ClassInstanceCheck(
                            fieldsFinal = listOf(FieldData("nimi", "'x'")),
                            checkName = true, checkValue = true, nothingElse = false,
                            beforeMessage = "before", passedMessage = "passed", failedMessage = "failed",
                        )
                    ),
                    outputFileChecks = listOf(fileCheck), genericChecks = listOf(check),
                ),
            ).map { arrayOf(it::class.simpleName!!, it) }
        }
    }
}
