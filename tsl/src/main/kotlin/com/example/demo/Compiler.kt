package com.example.demo

import tsl.common.model.*

// class Compiler(private val irTree: IRTree) {
class Compiler(private val irTree: TSL) { // TODO: RemoveMe
    fun validateParseTree() {
        val allTestIds = this.irTree.tests.map { it.id }.toList()
        val duplicatedIds = allTestIds.filter { item -> allTestIds.count { it == item } > 1 }.toSet()
        if (duplicatedIds.isNotEmpty()) {
            throw Exception("Test ID-s must be unique within the exercise! Duplicates: $duplicatedIds")
        }
    }

    private fun validateNumber(genericChecks: List<GenericCheck>) {
        genericChecks.map {
            if (it.isNumeric == true) {
                it.expectedValue.map {
                    val reg = """((?:\+|\-)?\d+(?:(?:\.)\d+)?)""".toRegex()
                    if (!reg.matches(it)) {
                        throw Exception("The entered value (\"$it\") is not numeric in Python (make sure to use '.' instead of ',')!")
                    }
                }
            } else {
            }
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

    private fun generateAssessmentCode(test: Test, fileName: String): String {
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
                    PyList(test.arguments.map { PyStr(it, false) })
                }
                validateNumber(test.genericChecks)
                PyExecuteTest(
                    test,
                    "function_execution_test",
                    mapOf(
                        "file_name" to PyStr(fileName),
                        "function_name" to PyStr(test.functionName),
                        "function_type" to PyStr(test.functionType.toString()),
                        "create_object" to PyStr(test.createObject),
                        "arguments" to arguments,
                        "standard_input_data" to standardInputData,
                        "input_files" to inputFiles,
                        "return_value" to PyStr(test.returnValue, false),
                        "generic_checks" to PyGenericChecks(test.genericChecks),
                        "output_file_checks" to PyOutputTests(test.outputFileChecks)
                    )
                ).generatePyString()
            }

            is FunctionContainsLoopTest -> {
                PyExecuteTest(
                    test,
                    "function_contains_loop_test",
                    mapOf(
                        "file_name" to PyStr(fileName),
                        "function_name" to PyStr(test.functionName),
                        "contains_check" to PyBool(test.containsLoop.mustNotContain),
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
                        "file_name" to PyStr(fileName),
                        "function_name" to PyStr(test.functionName),
                        "generic_checks" to PyGenericChecks(test.genericCheck)
                    )
                ).generatePyString()
            }

            is FunctionContainsReturnTest -> {
                PyExecuteTest(
                    test,
                    "function_contains_return_test",
                    mapOf(
                        "file_name" to PyStr(fileName),
                        "function_name" to PyStr(test.functionName),
                        "contains_check" to PyBool(test.containsReturn.mustNotContain),
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
                        "file_name" to PyStr(fileName),
                        "function_name" to PyStr(test.functionName),
                        "generic_checks" to PyGenericChecksLong(test.genericCheck)
                    )
                ).generatePyString()
            }

            is FunctionCallsPrintTest -> {
                PyExecuteTest(
                    test,
                    "function_calls_print_test",
                    mapOf(
                        "file_name" to PyStr(fileName),
                        "function_name" to PyStr(test.functionName),
                        "contains_check" to PyBool(test.callsCheck.mustNotCall),
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
                        "file_name" to PyStr(fileName),
                        "function_name" to PyStr(test.functionName),
                        "contains_check" to PyBool(test.isRecursive.mustNotBeRecursive),
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
                        "file_name" to PyStr(fileName),
                        "function_name" to PyStr(test.functionName),
                        "generic_checks" to PyGenericChecksLong(test.genericCheck)
                    )
                ).generatePyString()
            }

            is FunctionImportsModuleTest -> {
                PyExecuteTest(
                    test,
                    "function_imports_module_test",
                    mapOf(
                        "file_name" to PyStr(fileName),
                        "function_name" to PyStr(test.functionName),
                        "generic_checks" to PyGenericChecksLong(test.genericCheck)
                    )
                ).generatePyString()
            }

            is FunctionContainsTryExceptTest -> {
                PyExecuteTest(
                    test,
                    "function_contains_try_except_test",
                    mapOf(
                        "file_name" to PyStr(fileName),
                        "function_name" to PyStr(test.functionName),
                        "contains_check" to PyBool(test.containsTryExcept.mustNotContain),
                        "before_message" to PyStr(test.containsTryExcept.beforeMessage),
                        "passed_message" to PyStr(test.containsTryExcept.passedMessage),
                        "failed_message" to PyStr(test.containsTryExcept.failedMessage)
                    )
                ).generatePyString()
            }

            is FunctionIsPureTest -> {
                PyExecuteTest(
                    test,
                    "function_is_pure_test",
                    mapOf(
                        "file_name" to PyStr(fileName),
                        "function_name" to PyStr(test.functionName),
                        "contains_check" to PyBool(test.containsLocalVars.mustNotContain),
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
                validateNumber(test.genericChecks)
                PyExecuteTest(
                    test,
                    "program_execution_test",
                    mapOf(
                        "file_name" to PyStr(fileName),
                        "standard_input_data" to standardInputData,
                        "input_files" to inputFiles,
                        "generic_checks" to PyGenericChecks(test.genericChecks),
                        "output_file_checks" to PyOutputTests(test.outputFileChecks),
                        "exception_check" to PyBool(test.exceptionCheck?.mustNotThrowException),
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
                        "file_name" to PyStr(fileName),
                        "contains_check" to PyBool(test.programContainsTryExcept.mustNotContain),
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
                        "file_name" to PyStr(fileName),
                        "contains_check" to PyBool(test.programCallsPrint.mustNotCall),
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
                        "file_name" to PyStr(fileName),
                        "contains_check" to PyBool(test.programContainsLoop.mustNotContain),
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
                        "file_name" to PyStr(fileName),
                        "generic_checks" to PyGenericChecksLong(test.genericCheck)
                    )
                ).generatePyString()
            }

            is ProgramContainsKeywordTest -> {
                PyExecuteTest(
                    test,
                    "program_contains_keyword_test",
                    mapOf(
                        "file_name" to PyStr(fileName),
                        "generic_checks" to PyGenericChecks(test.genericCheck)
                    )
                ).generatePyString()
            }

            is ProgramCallsFunctionTest -> {
                PyExecuteTest(
                    test,
                    "program_calls_function_test",
                    mapOf(
                        "file_name" to PyStr(fileName),
                        "generic_checks" to PyGenericChecksLong(test.genericCheck)
                    )
                ).generatePyString()
            }

            is ProgramDefinesFunctionTest -> {
                PyExecuteTest(
                    test,
                    "program_defines_function_test",
                    mapOf(
                        "file_name" to PyStr(fileName),
                        "generic_checks" to PyGenericChecksLong(test.genericCheck)
                    )
                ).generatePyString()
            }

            is ClassImportsModuleTest -> {
                PyExecuteTest(
                    test,
                    "class_imports_module_test",
                    mapOf(
                        "file_name" to PyStr(fileName),
                        "class_name" to PyStr(test.className),
                        "generic_checks" to PyGenericChecksLong(test.genericCheck)
                    )
                ).generatePyString()
            }

            is ClassDefinesFunctionTest -> {
                PyExecuteTest(
                    test,
                    "class_defines_function_test",
                    mapOf(
                        "file_name" to PyStr(fileName),
                        "class_name" to PyStr(test.className),
                        "generic_checks" to PyGenericChecksLong(test.genericCheck)
                    )
                ).generatePyString()
            }

            is ClassCallsClassTest -> {
                PyExecuteTest(
                    test,
                    "class_calls_class_test",
                    mapOf(
                        "file_name" to PyStr(fileName),
                        "class_name" to PyStr(test.className),
                        "generic_checks" to PyGenericChecksLong(test.genericCheck)
                    )
                ).generatePyString()
            }

            is ClassFunctionCallsFunctionTest -> {
                PyExecuteTest(
                    test,
                    "class_function_calls_function_test",
                    mapOf(
                        "file_name" to PyStr(fileName),
                        "class_name" to PyStr(test.className),
                        "class_function_name" to PyStr(test.classFunctionName),
                        "generic_checks" to PyGenericChecksLong(test.genericCheck)
                    )
                ).generatePyString()
            }

            is ProgramDefinesClassTest -> {
                PyExecuteTest(
                    test,
                    "program_defines_class_test",
                    mapOf(
                        "file_name" to PyStr(fileName),
                        "generic_checks" to PyGenericChecksLong(test.genericCheck)
                    )
                ).generatePyString()
            }

            is ProgramDefinesSubclassTest -> {
                PyExecuteTest(
                    test,
                    "program_defines_subclass_test",
                    mapOf(
                        "file_name" to PyStr(fileName),
                        "class_name" to PyStr(test.className),
                        "superclass_name" to PyStr(test.superClass),
                        "before_message" to PyStr(test.beforeMessage),
                        "passed_message" to PyStr(test.passedMessage),
                        "failed_message" to PyStr(test.failedMessage),
                    )
                ).generatePyString()
            }

            is ProgramCallsClassTest -> {
                PyExecuteTest(
                    test,
                    "program_calls_class_test",
                    mapOf(
                        "file_name" to PyStr(fileName),
                        "generic_checks" to PyGenericChecksLong(test.genericCheck)
                    )
                ).generatePyString()
            }

            is ProgramCallsClassFunctionTest -> {
                PyExecuteTest(
                    test,
                    "program_calls_class_function_test",
                    mapOf(
                        "file_name" to PyStr(fileName),
                        "generic_checks" to PyGenericChecksLong(test.genericCheck)
                    )
                ).generatePyString()
            }

            is ClassInstanceTest -> {
                val fieldsFinal: PyList =
                    test.fieldsFinal.map { PyPair(PyStr(it.fieldName), PyStr(it.fieldContent, forceString = false)) }
                        .let { PyList(it) }
                PyExecuteTest(
                    test,
                    "class_instance_test",
                    mapOf(
                        "file_name" to PyStr(fileName),
                        "class_name" to PyStr(test.className),
                        "create_object" to PyStr(test.createObject),
                        "fields_final" to fieldsFinal,
                        "before_message" to PyStr(test.beforeMessage),
                        "passed_message" to PyStr(test.passedMessage),
                        "failed_message" to PyStr(test.failedMessage)
                    )
                ).generatePyString()
            }

            else -> throw Exception("Unknown Test: $test")
        }
    }
}