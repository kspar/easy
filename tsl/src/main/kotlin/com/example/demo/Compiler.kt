package com.example.demo

import kotlin.reflect.full.memberProperties

class Compiler(private val irTree: IRTree) {

    private fun <T> formatField(item: T): Any {
        return when (item) {
            // TODO issue: doesn't work when string contains ' or "?
            // TODO: add tests where this is the case
            is String -> {
                "'$item'"
            }
            null -> {
                "None"
            }
            // TODO issue: does it work with all notations, e.g. scientific?
            //  val a = 100000000000.99999999999
            //  a.toString()  // 1.00000000001E11
            is Float, is Int, is Double -> {
                item
            }
            is List<*> -> {
                var builder = "["
                item.map { element ->
                    // TODO issue: elements always strings?
                    builder = builder.plus("'${element}',")
                }
                // TODO bad style - don't append , at all for last element instead of deleting it afterwards
                builder.dropLast(1).plus("]")
            }
            // TODO which cases does this handle?
            else -> "'$item'"
        }
    }

    private fun <T> formatNullField(item: T): Any {
        return when (item) {
            null -> {
                "None"
            }
            else -> item
        }
    }

    // Generate FileCheck code
    private fun generateFileCheckCode(fileCheck: IRFileCheck): String {
        return "file_test = File(${formatField(fileCheck.fileName)})\n" +
                generateTestCode(fileCheck.tests.filterIsInstance<IRFileExists>(), "file_test", null) +
                generateTestCode(fileCheck.tests.filterIsInstance<IRFileNotEmpty>(), "file_test", null) +
                generateTestCode(fileCheck.tests.filterIsInstance<IRFileIsPython>(), "file_test", null)
    }

    // Generate ProgramCheck code
    private fun generateProgramStaticCheckCode(programCheck: IRProgramStaticCheck): String {
        return "program_test = Program('${programCheck.fileName}')\n" + generateTestCode(
            programCheck.tests.filterIsInstance<IRProgramDefinesFunction>(), "program_test", listOf("functionName")
        ) + generateTestCode(
            programCheck.tests.filterIsInstance<IRProgramDefinesAnyFunction>(), "program_test", null
        ) + generateTestCode(
            programCheck.tests.filterIsInstance<IRProgramImportsModule>(), "program_test", listOf("moduleName")
        ) + generateTestCode(
            programCheck.tests.filterIsInstance<IRProgramImportsModuleFromSet>(), "program_test", listOf("moduleNames")
        ) + generateTestCode(
            programCheck.tests.filterIsInstance<IRProgramImportsAnyModule>(), "program_test", null
        ) + generateTestCode(
            programCheck.tests.filterIsInstance<IRProgramCallsFunction>(), "program_test", listOf("functionName")
        ) + generateTestCode(
            programCheck.tests.filterIsInstance<IRProgramCallsFunctionFromSet>(),
            "program_test", listOf("functionsList")
        ) + generateTestCode(
            programCheck.tests.filterIsInstance<IRProgramContainsLoop>(), "program_test", null
        ) + generateTestCode(
            programCheck.tests.filterIsInstance<IRProgramContainsTryExcept>(), "program_test", null
        ) + generateTestCode(programCheck.tests.filterIsInstance<IRProgramCallsPrint>(), "program_test", null)
    }

    // Generate ProgramCheck code
    private fun generateProgramExecutionCheckCode(programChecks: List<IRProgramExecutionCheck>?): String {
        if (programChecks == null) {
            return ""
        }
        val builder = StringBuilder()
        programChecks.map { programCheck ->
            if (programCheck.inputFiles != null) {
                programCheck.inputFiles.map { inputFile ->
                    builder.append("create_input_file(${formatField(inputFile.fileName)}, ${formatField(inputFile.fileContent)})\n")
                }
            }
            builder.append("program = Program('${programCheck.fileName}')\n")
            builder.append(
                "program_execution = ProgramExecution(program, ${formatField(programCheck.userInputs)}, ${
                    formatField(
                        programCheck.expectedOutput
                    )
                })\n"
            )
            builder.append("execute_test(program_execution.program_execution_and_input_count, 0.0, None, None, None, None, None)\n")
            val programTest = "program_execution"
            // builder.append(generateTestCode(programCheck.tests.filterIsInstance<IRProgramDefinesFunction>(), programTest, listOf("functionName")))
            builder.append(
                generateTestCode(
                    programCheck.tests.filterIsInstance<IRProgramInputCountCorrect>(),
                    programTest,
                    null
                )
            )
            builder.append(
                generateTestCode(
                    programCheck.tests.filterIsInstance<IRProgramOutputCorrect>(),
                    programTest,
                    null
                )
            )
            builder.append(
                generateTestCode(
                    programCheck.tests.filterIsInstance<IRProgramRaisedException>(),
                    programTest,
                    null
                )
            )
            if (programCheck.outputFiles != null) {
                programCheck.outputFiles.map { outputFile ->
                    builder.append(
                        generateOutputFileTestCode(
                            programCheck.tests.filterIsInstance<IRProgramOutputFileCorrect>(),
                            programTest,
                            outputFile
                        )
                    )
                }
            }
        }
        return builder.toString()
    }

    // Generate FunctionCheck code
    private fun generateFunctionStaticCheckCode(functionCheck: List<IRFunctionStaticCheck>?): String {
        if (functionCheck == null) {
            return ""
        }
        val builder = StringBuilder()

        functionCheck.map { function ->
            builder.append("program_test = Program('${function.fileName}')\n")
            val functionTest = "function_test"
            builder.append("$functionTest = Function(${formatField(function.functionName)}, program_test.get_syntax_tree())\n")
            builder.append(
                generateTestCode(
                    function.tests.filterIsInstance<IRFunctionParamsCountCorrect>(),
                    functionTest,
                    listOf("numberOfParams")
                )
            )
            builder.append(
                generateTestCode(
                    function.tests.filterIsInstance<IRFunctionCallsFunction>(),
                    functionTest,
                    listOf("functionName")
                )
            )
            builder.append(
                generateTestCode(
                    function.tests.filterIsInstance<IRFunctionCallsFunctionFromSet>(),
                    functionTest,
                    listOf("functionsList")
                )
            )
            builder.append(
                generateTestCode(
                    function.tests.filterIsInstance<IRFunctionImportsModule>(),
                    functionTest,
                    listOf("moduleName")
                )
            )
            builder.append(
                generateTestCode(
                    function.tests.filterIsInstance<IRFunctionImportsAnyModule>(),
                    functionTest,
                    null
                )
            )
            builder.append(
                generateTestCode(
                    function.tests.filterIsInstance<IRFunctionDefinesFunction>(),
                    functionTest,
                    listOf("functionName")
                )
            )
            builder.append(
                generateTestCode(
                    function.tests.filterIsInstance<IRFunctionDefinesAnyFunction>(),
                    functionTest,
                    null
                )
            )
            builder.append(
                generateTestCode(
                    function.tests.filterIsInstance<IRFunctionCallsPrint>(),
                    functionTest,
                    null
                )
            )
            builder.append(
                generateTestCode(
                    function.tests.filterIsInstance<IRFunctionContainsReturn>(),
                    functionTest,
                    null
                )
            )
            builder.append(
                generateTestCode(
                    function.tests.filterIsInstance<IRFunctionContainsLoop>(),
                    functionTest,
                    null
                )
            )
            builder.append(
                generateTestCode(
                    function.tests.filterIsInstance<IRFunctionIsRecursive>(),
                    functionTest,
                    null
                )
            )
        }

        return builder.toString()
    }

    // Generate FunctionCheck code (can be multiple functionChecks per TSL)
    private fun generateFunctionExecutionCheckCode(functionCheck: List<IRFunctionExecutionCheck>?): String {
        if (functionCheck == null) {
            return ""
        }
        val builder = StringBuilder()

        functionCheck.map { function ->
            builder.append("program_test = Program('${function.fileName}')\n")
            builder.append("program_execution = ProgramExecution(program_test, ${formatField(function.userInputsProgram)})\n")
            builder.append("execute_test(program_execution.program_execution_and_input_count, 0.0, None, None, None, None, None)\n")
            builder.append("function_object = program_execution.globals_dict.get(${formatField(function.functionName)}, None)\n")
            builder.append(
                "function_execution = FunctionExecution(function_object, ${function.arguments}, ${
                    formatField(
                        function.userInputsFunction
                    )
                }, ${formatNullField(function.expectedOutput)})\n"
            )
            builder.append("execute_test(function_execution.function_execution_and_input_count, 0.0, None, None, None, None, None)\n")
            val functionTest = "function_execution"
            // builder.append("$functionTest = FunctionExecution(${formatField(function.functionName)}, program_test.get_syntax_tree())\n")
            builder.append(generateTestCode(function.tests.filterIsInstance<IRFunctionReturned>(), functionTest, null))
            builder.append(
                generateTestCode(
                    function.tests.filterIsInstance<IRFunctionInputCountCorrect>(),
                    functionTest,
                    null
                )
            )
            builder.append(
                generateTestCode(
                    function.tests.filterIsInstance<IRFunctionReturnTypeCorrect>(),
                    functionTest,
                    listOf("expectedReturnType")
                )
            )
            builder.append(
                generateTestCode(
                    function.tests.filterIsInstance<IRFunctionReturnValueCorrect>(),
                    functionTest,
                    null
                )
            )
            builder.append(
                generateTestCode(
                    function.tests.filterIsInstance<IRFunctionRaisedException>(),
                    functionTest,
                    null
                )
            )
        }

        return builder.toString()
    }

    private fun generateTestCode(tests: List<IRTest>, testObject: String, fieldsToPass: List<String>?): String {
        if (tests.isEmpty()) {
            return ""
        }
        val builder = StringBuilder()
        tests.map { test ->
            builder.append(
                "execute_test($testObject.${test.testNamePython}, ${formatField(test.points)}, " +
                        "${formatField(test.beforeMessage)}, ${formatField(test.failedMessage)}, " +
                        "${formatField(test.passedMessage)}, ${formatField(test.name)}, " +
                        "${formatField(test.inputs)}"
            )
            if (!fieldsToPass.isNullOrEmpty()) {
                fieldsToPass.map { field ->
                    builder.append(
                        ", ${
                            formatField(test.javaClass.kotlin.memberProperties.first { it.name == field }.get(test))
                        }"
                    )
                }
            }
            builder.append(")\n")
        }
        return builder.toString()
    }

    private fun generateOutputFileTestCode(
        tests: List<IRTest>,
        testObject: String,
        outputFile: TestFile
    ): String { // TODO: Create IRTestFile
        if (tests.isEmpty()) {
            return ""
        }

        return "execute_test($testObject.${tests[0].testNamePython}, ${formatField(tests[0].points)}, " +
                "${formatField(tests[0].beforeMessage)}, ${formatField(tests[0].failedMessage)}, " +
                "${formatField(tests[0].passedMessage)}, ${formatField(tests[0].name)}, " +
                "${formatField(tests[0].inputs)}, ${formatField(outputFile.fileName)}, ${formatField(outputFile.fileContent)})\n"
    }

    fun generateAssessmentCode(): String {
        return "from lib import *\n" +
                generateFileCheckCode(irTree.fileCheck) +
                generateProgramStaticCheckCode(irTree.programStaticCheck) +
                generateProgramExecutionCheckCode(irTree.programExecutionCheck) +
                generateFunctionStaticCheckCode(irTree.functionStaticCheck) +
                generateFunctionExecutionCheckCode(irTree.functionExecutionCheck) +
                "print(Results(None).val)\n"
    }

}
