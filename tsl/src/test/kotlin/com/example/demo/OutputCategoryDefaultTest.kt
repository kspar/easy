package com.example.demo

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The `outputCategory` default is ALL_OUTPUT — and that is a *decision*, not an accident of the
 * model (EZ-1742 / beta item B11, decided 2026-08-30).
 *
 * Under the old ALL_IO default a check compared against the program's output *and* the input the
 * test itself fed in, so a student who echoed every candidate answer passed. Measured directly on
 * the dev corpus (after-dev.jsonl, 2026-08-30): all 3081 genericChecks omit the key — the editor
 * and the migration write plain JSON, so `encodeDefaults` never applied — meaning the default is
 * what every existing exercise actually uses, and each adopts ALL_OUTPUT on its next save or
 * recompile. That is the change the decision accepted; checks that lean on the input echo (the
 * KT2_jalgpall shape) need an explicit ALL_IO. The golden specs all set the key explicitly, so
 * without this test nothing anywhere would notice the default quietly reverting.
 */
class OutputCategoryDefaultTest {

    private fun compile(spec: String): String =
        compileTSL(spec, "1.0.0", "tiivad", TSLSpecFormat.JSON).generatedScripts.single()

    private fun spec(outputCategoryLine: String) = """
        {
          "validateFiles": false,
          "requiredFiles": ["lahendus.py"],
          "tslVersion": "1.0.0",
          "tests": [
            {
              "type": "program_execution_test",
              "id": 1,
              "standardInputData": [],
              "inputFiles": [],
              "genericChecks": [
                {
                  "id": 2,
                  "checkType": "ALL_OF_THESE",
                  $outputCategoryLine
                  "expectedValue": ["42"],
                  "beforeMessage": "checking",
                  "passedMessage": "passed",
                  "failedMessage": "failed"
                }
              ],
              "outputFileChecks": [],
              "exceptionCheck": null
            }
          ]
        }
    """.trimIndent()

    @Test
    @DisplayName("a check without an explicit outputCategory judges output, not the test's own input")
    fun defaultIsAllOutput() {
        val generated = compile(spec(""))
        assertTrue(generated.contains("'output_category':'''ALL_OUTPUT'''")) { generated.take(2000) }
    }

    @Test
    @DisplayName("an explicit ALL_IO is preserved — old exercises that want it can say so")
    fun explicitAllIoSurvives() {
        val generated = compile(spec("\"outputCategory\": \"ALL_IO\","))
        assertTrue(generated.contains("'output_category':'''ALL_IO'''")) { generated.take(2000) }
    }
}
