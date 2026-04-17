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

    fun generateAssessmentCodes(): String {
        if (irTree.requiredFiles.isEmpty()) {
            throw Exception("The requiredFiles list cannot be empty.")
        }

        val assessmentCode = "from tiivad import *\n"
        var validationCode = ""
        if (irTree.validateFiles) {
            validationCode = generateValidationCode(irTree.requiredFiles)
        }
        var assCode = ""
        this.irTree.tests.map {
            assCode += generateAssessmentCode(it, irTree.requiredFiles[0]) + "\n"
        }

        val printCode = "print(Results(None))\n"
        //println("print(json.dumps(Results(None).format_result(), cls=ComplexEncoder, ensure_ascii=False))\n" + // TODO: FIXME
        //        "with open('a1_results_real.json', 'w', encoding='utf-8') as f: f.write(json.dumps(Results(None).format_result(), cls=ComplexEncoder, ensure_ascii=False))")
        return "$assessmentCode$validationCode$assCode$printCode"
    }

    private fun generateValidationCode(filesToValidate: List<String>): String {
        return filesToValidate.joinToString(", ", "validate_files([", "])\n") { PyStr(it).generatePyString() }
    }

    private fun generateAssessmentCode(test: Test, fileName: String): String = when (test) {
        is CallsTest ->
            PyExecuteTest(
                test, "calls_test", mapOf(
                    "file_name" to PyStr(fileName),
                    "contains_checks" to PyGenericChecksLong(test.genericCheck),

                    "scope" to PyStr(test.scope.value),
                    "target" to PyStr(test.targetType.value),

                    "scope_function_name" to PyStr(test.functionName),
                    "scope_class_name" to PyStr(test.className),
                )
            ).generatePyString()

        is ContainsTest ->
            PyExecuteTest(
                test,
                "contains_test",
                mapOf(
                    "file_name" to PyStr(fileName),
                    "contains_checks" to PyGenericChecksLong(test.genericCheck),

                    "contains_what" to PyStr(test.containsWhat.name),
                    "contains_what_arg" to PyStr(test.containsWhatArg),

                    "scope" to PyStr(test.scope.value),
                    "scope_class_name" to PyStr(test.className),
                    "scope_function_name" to PyStr(test.functionName),
                )
            ).generatePyString()


        is FunctionIsTest ->
            PyExecuteTest(
                test,
                "function_is_test",
                mapOf(
                    "file_name" to PyStr(fileName),
                    "function_name" to PyStr(test.functionName),
                    "function_property" to PyStr(test.functionProperty.name)
                )
            ).generatePyString()


        is DefinitionTest ->
            PyExecuteTest(
                test,
                "definition_test",
                mapOf(
                    "file_name" to PyStr(fileName),
                    "function_name" to PyStr(test.functionName),
                    "class_name" to PyStr(test.className),
                    "definition_check_value" to PyStr(test.definitionCheckValue),
                    "super_class_name" to PyStr(test.superClassName),
                    "definition_check_type" to PyStr(test.definitionCheckType.name)
                )
            ).generatePyString()

        is FunctionExecutionTest -> {
            val standardInputData = PyList(test.standardInputData.map { PyStr(it) })

            val inputFiles: PyList = test
                .inputFiles
                .map { PyPair(PyStr(it.fileName), PyStr(it.fileContent)) }
                .let { PyList(it) }

            val arguments = PyList(test.arguments.map { PyStr(it, false) })

            val returnValueChecks: PyList = if (test.returnValueCheck == null) {
                PyList(listOf())
            } else {
                PyList(
                    listOf(
                        PyDict(
                            mapOf(
                                "'expected_value'" to PyStr(test.returnValueCheck?.returnValue, false),
                                "'before_message'" to PyStr(test.returnValueCheck?.beforeMessage),
                                "'passed_message'" to PyStr(test.returnValueCheck?.passedMessage),
                                "'failed_message'" to PyStr(test.returnValueCheck?.failedMessage)
                            )
                        )
                    )
                )
            }
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
                    "return_value_checks" to returnValueChecks,
                    "param_value_checks" to PyList(
                        test.paramValueChecks.map {
                            PyDict(
                                mapOf(
                                    "param_number" to PyStr(it.paramNumber.toString()),
                                    "expected_value" to PyStr(it.expectedValue),
                                    "before_message" to PyStr(it.beforeMessage),
                                    "passed_message" to PyStr(it.passedMessage),
                                    "failed_message" to PyStr(it.failedMessage)
                                )
                            )
                        }
                    ),
                    "standard_output_checks" to PyGenericChecks(test.genericChecks),
                    "output_file_checks" to PyOutputTests(test.outputFileChecks),
                    "out_of_inputs_error_msg" to PyStr(test.outOfInputsErrorMsg),
                    "function_not_defined_error_msg" to PyStr(test.functionNotDefinedErrorMsg),
                    "too_many_arguments_provided_error_msg" to PyStr(test.tooManyArgumentsProvidedErrorMsg)
                )
            ).generatePyString()
        }


        is ProgramExecutionTest -> {
            val standardInputData = PyList(test.standardInputData.map { PyStr(it) })

            val inputFiles: PyList = test
                .inputFiles
                .map { PyPair(PyStr(it.fileName), PyStr(it.fileContent)) }
                .let { PyList(it) }

            val exceptionCheck = if (test.exceptionCheck == null) {
                PyStr(null)
            } else {
                PyDict(
                    mapOf(
                        "'expected_value'" to PyBool(!test.exceptionCheck!!.mustNotThrowException),
                        "'before_message'" to PyStr(test.exceptionCheck!!.beforeMessage),
                        "'passed_message'" to PyStr(test.exceptionCheck!!.passedMessage),
                        "'failed_message'" to PyStr(test.exceptionCheck!!.failedMessage)
                    )
                )
            }
            PyExecuteTest(
                test,
                "program_execution_test",
                mapOf(
                    "file_name" to PyStr(fileName),
                    "standard_input_data" to standardInputData,
                    "input_files" to inputFiles,
                    "standard_output_checks" to PyGenericChecks(test.genericChecks),
                    "output_file_checks" to PyOutputTests(test.outputFileChecks),
                    "exception_check" to exceptionCheck
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
                    "contains_checks" to PyGenericChecksLong(test.genericCheck)
                )
            ).generatePyString()
        }

        is ClassInstanceTest -> {
            PyExecuteTest(
                test,
                "class_instance_test",
                mapOf(
                    "file_name" to PyStr(fileName),
                    "class_name" to PyStr(test.className),
                    "create_object" to PyStr(test.createObject),
                    "class_instance_checks" to PyList(
                        test.classInstanceChecks.map {
                            PyDict(
                                mapOf(
                                    "'fields_final'" to PyList(it.fieldsFinal.map {
                                        PyPair(
                                            PyStr(it.fieldName),
                                            PyStr(it.fieldContent, forceString = false)
                                        )
                                    }),
                                    "'check_name'" to PyBool(it.checkName),
                                    "'check_value'" to PyBool(it.checkValue),
                                    "'nothing_else'" to PyBool(it.nothingElse),
                                    "'before_message'" to PyStr(it.beforeMessage),
                                    "'passed_message'" to PyStr(it.passedMessage),
                                    "'failed_message'" to PyStr(it.failedMessage)
                                )
                            )
                        }
                    ),
                    "standard_output_checks" to PyGenericChecks(test.genericChecks),
                    "output_file_checks" to PyOutputTests(test.outputFileChecks),
                )

            ).generatePyString()
        }


        // Only used as an empty placeholder test - the user hasn't decided on the type yet
        is PlaceholderTest -> ""
    }
}