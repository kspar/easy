package com.example.demo

// class Compiler(private val irTree: IRTree) {
class Compiler(private val irTree: TSL) { // TODO: RemoveMe

    fun generateAssessmentCodes(): String {
        if (irTree.validateFiles) {
            val validationCode = generateValidationCode(irTree.requiredFiles)
            println(validationCode)
        }
        val a = this.irTree.tests.map {
            println(generateAssessmentCode(it, irTree.requiredFiles[0]))
        }.joinToString { "\n" }
        println(a)
        return ""
    }

    private fun generateValidationCode(filesToValidate: List<String>): String {
        return filesToValidate.joinToString(", ", "validate_files([", "])") { PyStr(it).generatePyString() }
    }

    private fun generateAssessmentCode(test: Test, file_name: String): String {
        return when (test) {
            is FunctionExecutionTest -> {
                PyExecuteTest(
                    "function_execution_test",
                    mapOf(
                        "file_name" to PyStr(file_name),
                        "function_name" to PyStr(test.functionName),
                        "arguments" to PyStr(test.arguments),
                        "standard_input_data" to PyStr(test.standardInputData),
                        "input_files" to test.inputFiles?.map { PyPair(PyStr(it.fileName), PyStr(it.fileContent)) }
                            ?.let { PyList(it) },
                        "return_value" to PyStr(test.returnValue),
                        "standard_output_checks" to PyStandardOutputChecks(test.standardOutputChecks),
                        "output_file_checks" to PyOutputTests(test.outputFileChecks)
                    )
                ).generatePyString()
            }
            is FunctionContainsLoopTest -> {
                PyExecuteTest(
                    "function_contains_loop_test",
                    mapOf(
                        "file_name" to PyStr(file_name),
                        "function_name" to PyStr(test.functionName),
                        "contains_check" to PyPair(
                            PyBool(test.containsLoop.mustContain),
                            PyBool(test.containsLoop.cannotContain)
                        )
                    )
                ).generatePyString()
            }
            is FunctionContainsKeywordTest -> {
                PyExecuteTest(
                    "function_contains_keyword_test",
                    mapOf(
                        "file_name" to PyStr(file_name),
                        "function_name" to PyStr(test.functionName),
                        "standard_output_checks" to PyStandardOutputChecks(test.standardOutputCheck)
                    )
                ).generatePyString()
            }
            is FunctionContainsReturnTest -> {
                PyExecuteTest(
                    "function_contains_return_test",
                    mapOf(
                        "file_name" to PyStr(file_name),
                        "function_name" to PyStr(test.functionName),
                        "contains_check" to PyPair(
                            PyBool(test.containsReturn.mustContain),
                            PyBool(test.containsReturn.cannotContain)
                        )
                    )
                ).generatePyString()
            }
            is FunctionCallsFunctionTest -> {
                PyExecuteTest(
                    "function_calls_function_test",
                    mapOf(
                        "file_name" to PyStr(file_name),
                        "function_name" to PyStr(test.functionName),
                        "standard_output_checks" to PyStandardOutputChecksLong(test.standardOutputCheck)
                    )
                ).generatePyString()
            }
            is FunctionIsRecursiveTest -> {
                PyExecuteTest(
                    "function_is_recursive_test",
                    mapOf(
                        "file_name" to PyStr(file_name),
                        "function_name" to PyStr(test.functionName),
                        "contains_check" to PyPair(
                            PyBool(test.isRecursive.mustBeRecursive),
                            PyBool(test.isRecursive.cannotBeRecursive)
                        )
                    )
                ).generatePyString()
            }
            is FunctionDefinesFunctionTest -> {
                PyExecuteTest(
                    "function_defines_function_test",
                    mapOf(
                        "file_name" to PyStr(file_name),
                        "function_name" to PyStr(test.functionName),
                        "standard_output_checks" to PyStandardOutputChecksLong(test.standardOutputCheck)
                    )
                ).generatePyString()
            }
            is FunctionImportsModuleTest -> {
                PyExecuteTest(
                    "function_imports_module_test",
                    mapOf(
                        "file_name" to PyStr(file_name),
                        "function_name" to PyStr(test.functionName),
                        "standard_output_checks" to PyStandardOutputChecksLong(test.standardOutputCheck)
                    )
                ).generatePyString()
            }
            is FunctionContainsTryExceptTest -> {
                PyExecuteTest(
                    "function_contains_try_except_test",
                    mapOf(
                        "file_name" to PyStr(file_name),
                        "function_name" to PyStr(test.functionName),
                        "contains_check" to PyPair(
                            PyBool(test.containsTryExcept.mustContain),
                            PyBool(test.containsTryExcept.cannotContain)
                        )
                    )
                ).generatePyString()
            }
            is FunctionUsesOnlyLocalVarsTest -> {
                PyExecuteTest(
                    "function_uses_only_local_vars_test",
                    mapOf(
                        "file_name" to PyStr(file_name),
                        "function_name" to PyStr(test.functionName),
                        "contains_check" to PyPair(
                            PyBool(test.containsLocalVars.mustContain),
                            PyBool(test.containsLocalVars.cannotContain)
                        )
                    )
                ).generatePyString()
            }
            is ProgramExecutionTest -> {
                PyExecuteTest(
                    "program_execution_test",
                    mapOf(
                        "file_name" to PyStr(file_name),
                        "standard_input_data" to PyStr(test.standardInputData),
                        "input_files" to test.inputFiles?.map { PyPair(PyStr(it.fileName), PyStr(it.fileContent)) }
                            ?.let { PyList(it) },
                        "standard_output_checks" to PyStandardOutputChecks(test.standardOutputChecks),
                        "output_file_checks" to PyOutputTests(test.outputFileChecks),
                        "exception_check" to PyPair(
                            PyBool(test.exceptionCheck?.mustThrowException),
                            PyBool(test.exceptionCheck?.cannotThrowException)
                        )
                    )
                ).generatePyString()
            }
            is ProgramContainsTryExceptTest -> {
                PyExecuteTest(
                    "program_contains_try_except_test",
                    mapOf(
                        "file_name" to PyStr(file_name),
                        "contains_check" to PyPair(
                            PyBool(test.programContainsTryExcept.mustContain),
                            PyBool(test.programContainsTryExcept.cannotContain)
                        )
                    )
                ).generatePyString()
            }
            is ProgramCallsPrintTest -> {
                PyExecuteTest(
                    "program_calls_print_test",
                    mapOf(
                        "file_name" to PyStr(file_name),
                        "contains_check" to PyPair(
                            PyBool(test.programCallsPrint.mustContain),
                            PyBool(test.programCallsPrint.cannotContain)
                        )
                    )
                ).generatePyString()
            }
            is ProgramContainsLoopTest -> {
                PyExecuteTest(
                    "program_contains_loop_test",
                    mapOf(
                        "file_name" to PyStr(file_name),
                        "contains_check" to PyPair(
                            PyBool(test.programContainsLoop.mustContain),
                            PyBool(test.programContainsLoop.cannotContain)
                        )
                    )
                ).generatePyString()
            }
            is ProgramImportsModuleTest -> {
                PyExecuteTest(
                    "program_imports_module_test",
                    mapOf(
                        "file_name" to PyStr(file_name),
                        "standard_output_checks" to PyStandardOutputChecksLong(test.standardOutputCheck)
                    )
                ).generatePyString()
            }
            is ProgramContainsKeywordTest -> {
                PyExecuteTest(
                    "program_contains_keyword_test",
                    mapOf(
                        "file_name" to PyStr(file_name),
                        "standard_output_checks" to PyStandardOutputChecks(test.standardOutputCheck)
                    )
                ).generatePyString()
            }
            is ProgramCallsFunctionTest -> {
                PyExecuteTest(
                    "program_calls_function_test",
                    mapOf(
                        "file_name" to PyStr(file_name),
                        "standard_output_checks" to PyStandardOutputChecksLong(test.standardOutputCheck)
                    )
                ).generatePyString()
            }
            is ProgramDefinesFunctionTest -> {
                PyExecuteTest(
                    "program_defines_function_test",
                    mapOf(
                        "file_name" to PyStr(file_name),
                        "standard_output_checks" to PyStandardOutputChecksLong(test.standardOutputCheck)
                    )
                ).generatePyString()
            }

            else -> "Unknown Test"
        }
    }
}