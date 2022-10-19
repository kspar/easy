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
                    test,
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
                    test,
                    "function_contains_loop_test",
                    mapOf(
                        "file_name" to PyStr(file_name),
                        "function_name" to PyStr(test.functionName),
                        "contains_check" to PyPair(
                            PyBool(test.containsLoop.mustContain),
                            PyBool(test.containsLoop.cannotContain)
                        ),
                        "before_message" to PyStr(test.containsLoop.beforeMessage),
                        "passedMessage" to PyStr(test.containsLoop.passedMessage),
                        "failedMessage" to PyStr(test.containsLoop.failedMessage)
                    )
                ).generatePyString()
            }
            is FunctionContainsKeywordTest -> {
                PyExecuteTest(
                    test,
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
                    test,
                    "function_contains_return_test",
                    mapOf(
                        "file_name" to PyStr(file_name),
                        "function_name" to PyStr(test.functionName),
                        "contains_check" to PyPair(
                            PyBool(test.containsReturn.mustContain),
                            PyBool(test.containsReturn.cannotContain)
                        ),
                        "before_message" to PyStr(test.containsReturn.beforeMessage),
                        "passedMessage" to PyStr(test.containsReturn.passedMessage),
                        "failedMessage" to PyStr(test.containsReturn.failedMessage)
                    )
                ).generatePyString()
            }
            is FunctionCallsFunctionTest -> {
                PyExecuteTest(
                    test,
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
                    test,
                    "function_is_recursive_test",
                    mapOf(
                        "file_name" to PyStr(file_name),
                        "function_name" to PyStr(test.functionName),
                        "contains_check" to PyPair(
                            PyBool(test.isRecursive.mustBeRecursive),
                            PyBool(test.isRecursive.cannotBeRecursive)
                        ),
                        "before_message" to PyStr(test.isRecursive.beforeMessage),
                        "passedMessage" to PyStr(test.isRecursive.passedMessage),
                        "failedMessage" to PyStr(test.isRecursive.failedMessage)
                    )
                ).generatePyString()
            }
            is FunctionDefinesFunctionTest -> {
                PyExecuteTest(
                    test,
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
                    test,
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
                    test,
                    "function_contains_try_except_test",
                    mapOf(
                        "file_name" to PyStr(file_name),
                        "function_name" to PyStr(test.functionName),
                        "contains_check" to PyPair(
                            PyBool(test.containsTryExcept.mustContain),
                            PyBool(test.containsTryExcept.cannotContain)
                        ),
                        "before_message" to PyStr(test.containsTryExcept.beforeMessage),
                        "passedMessage" to PyStr(test.containsTryExcept.passedMessage),
                        "failedMessage" to PyStr(test.containsTryExcept.failedMessage)
                    )
                ).generatePyString()
            }
            is FunctionUsesOnlyLocalVarsTest -> {
                PyExecuteTest(
                    test,
                    "function_uses_only_local_vars_test",
                    mapOf(
                        "file_name" to PyStr(file_name),
                        "function_name" to PyStr(test.functionName),
                        "contains_check" to PyPair(
                            PyBool(test.containsLocalVars.mustContain),
                            PyBool(test.containsLocalVars.cannotContain)
                        ),
                        "before_message" to PyStr(test.containsLocalVars.beforeMessage),
                        "passedMessage" to PyStr(test.containsLocalVars.passedMessage),
                        "failedMessage" to PyStr(test.containsLocalVars.failedMessage)
                    )
                ).generatePyString()
            }
            is ProgramExecutionTest -> {
                PyExecuteTest(
                    test,
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
                        ),
                        "before_message" to PyStr(test.exceptionCheck?.beforeMessage),
                        "passedMessage" to PyStr(test.exceptionCheck?.passedMessage),
                        "failedMessage" to PyStr(test.exceptionCheck?.failedMessage)
                    )
                ).generatePyString()
            }
            is ProgramContainsTryExceptTest -> {
                PyExecuteTest(
                    test,
                    "program_contains_try_except_test",
                    mapOf(
                        "file_name" to PyStr(file_name),
                        "contains_check" to PyPair(
                            PyBool(test.programContainsTryExcept.mustContain),
                            PyBool(test.programContainsTryExcept.cannotContain)
                        ),
                        "before_message" to PyStr(test.programContainsTryExcept.beforeMessage),
                        "passedMessage" to PyStr(test.programContainsTryExcept.passedMessage),
                        "failedMessage" to PyStr(test.programContainsTryExcept.failedMessage)
                    )
                ).generatePyString()
            }
            is ProgramCallsPrintTest -> {
                PyExecuteTest(
                    test,
                    "program_calls_print_test",
                    mapOf(
                        "file_name" to PyStr(file_name),
                        "contains_check" to PyPair(
                            PyBool(test.programCallsPrint.mustContain),
                            PyBool(test.programCallsPrint.cannotContain)
                        ),
                        "before_message" to PyStr(test.programCallsPrint.beforeMessage),
                        "passedMessage" to PyStr(test.programCallsPrint.passedMessage),
                        "failedMessage" to PyStr(test.programCallsPrint.failedMessage)
                    )
                ).generatePyString()
            }
            is ProgramContainsLoopTest -> {
                PyExecuteTest(
                    test,
                    "program_contains_loop_test",
                    mapOf(
                        "file_name" to PyStr(file_name),
                        "contains_check" to PyPair(
                            PyBool(test.programContainsLoop.mustContain),
                            PyBool(test.programContainsLoop.cannotContain)
                        ),
                        "before_message" to PyStr(test.programContainsLoop.beforeMessage),
                        "passedMessage" to PyStr(test.programContainsLoop.passedMessage),
                        "failedMessage" to PyStr(test.programContainsLoop.failedMessage)
                    )
                ).generatePyString()
            }
            is ProgramImportsModuleTest -> {
                PyExecuteTest(
                    test,
                    "program_imports_module_test",
                    mapOf(
                        "file_name" to PyStr(file_name),
                        "standard_output_checks" to PyStandardOutputChecksLong(test.standardOutputCheck)
                    )
                ).generatePyString()
            }
            is ProgramContainsKeywordTest -> {
                PyExecuteTest(
                    test,
                    "program_contains_keyword_test",
                    mapOf(
                        "file_name" to PyStr(file_name),
                        "standard_output_checks" to PyStandardOutputChecks(test.standardOutputCheck)
                    )
                ).generatePyString()
            }
            is ProgramCallsFunctionTest -> {
                PyExecuteTest(
                    test,
                    "program_calls_function_test",
                    mapOf(
                        "file_name" to PyStr(file_name),
                        "standard_output_checks" to PyStandardOutputChecksLong(test.standardOutputCheck)
                    )
                ).generatePyString()
            }
            is ProgramDefinesFunctionTest -> {
                PyExecuteTest(
                    test,
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