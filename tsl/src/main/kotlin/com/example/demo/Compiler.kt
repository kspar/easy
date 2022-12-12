package com.example.demo

// class Compiler(private val irTree: IRTree) {
class Compiler(private val irTree: TSL) { // TODO: RemoveMe

    fun validateParseTree() {
        val points = this.irTree.tests.sumOf { it.points }
        println("Total points: $points")
        if (points < 0 || points > 100) {
            throw Exception("The total number of points configured by UI ($points) is not in the valid range of [0..100].")
        }
    }

    fun generateAssessmentCodes(): String {
        val assessmentCode = "from tiivad import *\n"
        var validationCode = ""
        if (irTree.validateFiles) {
            validationCode = generateValidationCode(irTree.requiredFiles)
        }
        var assCode = ""
        this.irTree.tests.map {
            assCode += generateAssessmentCode(it, irTree.requiredFiles[0]) + "\n"
        }

        val printCode = "print(json.dumps(Results(None).format_result(), cls=ComplexEncoder, ensure_ascii=False))\n"
        //println("print(json.dumps(Results(None).format_result(), cls=ComplexEncoder, ensure_ascii=False))\n" + // TODO: FIXME
        //        "with open('a1_results_real.json', 'w', encoding='utf-8') as f: f.write(json.dumps(Results(None).format_result(), cls=ComplexEncoder, ensure_ascii=False))")
        return "$assessmentCode$validationCode$assCode$printCode"
    }

    private fun generateValidationCode(filesToValidate: List<String>): String {
        return filesToValidate.joinToString(", ", "validate_files([", "])\n") { PyStr(it).generatePyString() }
    }

    private fun generateAssessmentCode(test: Test, file_name: String): String {
        return when (test) {
            is FunctionExecutionTest -> {
                val standardInputData: PyList = if (test.standardInputData == null) {
                    PyList(listOf())
                } else {
                    PyList(test.standardInputData.map { PyStr(it) })
                }
                val inputFiles: PyList = if (test.inputFiles == null) {
                    PyList(listOf())
                } else {
                    test.inputFiles.map { PyPair(PyStr(it.fileName), PyStr(it.fileContent)) }.let { PyList(it) }
                }
                val arguments: PyList = if (test.arguments == null) {
                    PyList(listOf())
                } else {
                    PyList(test.arguments.map { PyStr(it) })
                }
                PyExecuteTest(
                    test,
                    "function_execution_test",
                    mapOf(
                        "file_name" to PyStr(file_name),
                        "function_name" to PyStr(test.functionName),
                        "arguments" to arguments,
                        "standard_input_data" to standardInputData,
                        "input_files" to inputFiles,
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
                        "passed_message" to PyStr(test.containsLoop.passedMessage),
                        "failed_message" to PyStr(test.containsLoop.failedMessage)
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
                        "passed_message" to PyStr(test.containsReturn.passedMessage),
                        "failed_message" to PyStr(test.containsReturn.failedMessage)
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
            is FunctionCallsPrintTest -> {
                PyExecuteTest(
                    test,
                    "function_calls_print_test",
                    mapOf(
                        "file_name" to PyStr(file_name),
                        "function_name" to PyStr(test.functionName),
                        "contains_check" to PyPair(
                            PyBool(test.callsCheck.mustCallPrint),
                            PyBool(test.callsCheck.cannotCallPrint)
                        ),
                        "before_message" to PyStr(test.callsCheck.beforeMessage),
                        "passed_message" to PyStr(test.callsCheck.passedMessage),
                        "failed_message" to PyStr(test.callsCheck.failedMessage)
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
                        "passed_message" to PyStr(test.isRecursive.passedMessage),
                        "failed_message" to PyStr(test.isRecursive.failedMessage)
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
                        "passed_message" to PyStr(test.containsTryExcept.passedMessage),
                        "failed_message" to PyStr(test.containsTryExcept.failedMessage)
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
                        "passed_message" to PyStr(test.containsLocalVars.passedMessage),
                        "failed_message" to PyStr(test.containsLocalVars.failedMessage)
                    )
                ).generatePyString()
            }
            is ProgramExecutionTest -> {
                val standardInputData: PyList = if (test.standardInputData == null) {
                    PyList(listOf())
                } else {
                    PyList(test.standardInputData.map { PyStr(it) })
                }
                val inputFiles: PyList = if (test.inputFiles == null) {
                    PyList(listOf())
                } else {
                    test.inputFiles.map { PyPair(PyStr(it.fileName), PyStr(it.fileContent)) }.let { PyList(it) }
                }
                PyExecuteTest(
                    test,
                    "program_execution_test",
                    mapOf(
                        "file_name" to PyStr(file_name),
                        "standard_input_data" to standardInputData,
                        "input_files" to inputFiles,
                        "standard_output_checks" to PyStandardOutputChecks(test.standardOutputChecks),
                        "output_file_checks" to PyOutputTests(test.outputFileChecks),
                        "exception_check" to PyPair(
                            PyBool(test.exceptionCheck?.mustThrowException),
                            PyBool(test.exceptionCheck?.cannotThrowException)
                        ),
                        "before_message" to PyStr(test.exceptionCheck?.beforeMessage),
                        "passed_message" to PyStr(test.exceptionCheck?.passedMessage),
                        "failed_message" to PyStr(test.exceptionCheck?.failedMessage)
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
                        "passed_message" to PyStr(test.programContainsTryExcept.passedMessage),
                        "failed_message" to PyStr(test.programContainsTryExcept.failedMessage)
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
                        "passed_message" to PyStr(test.programCallsPrint.passedMessage),
                        "failed_message" to PyStr(test.programCallsPrint.failedMessage)
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
                        "passed_message" to PyStr(test.programContainsLoop.passedMessage),
                        "failed_message" to PyStr(test.programContainsLoop.failedMessage)
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