package com.example.demo

// class Compiler(private val irTree: IRTree) {
class Compiler(private val irTree: TSL) { // TODO: RemoveMe

    fun generateAssessmentCodes(): String {
        var a = this.irTree.tests.map {
            println(generateAssessmentCode(it))
        }.joinToString { "\n" }
        println(a)
        return ""
        //return generateAssessmentCode(FunctionUsesOnlyLocalVarsTest(ContainsCheck(true, true)))
    }

    fun generateAssessmentCode(test: Test): String {
        return when (test) {
            is FunctionExecutionTest -> {
                PyExecuteTest(
                    "function_execution_test",
                    mapOf(
                        "file_name" to PyStr(""), // TODO: Kuidas me saame siin mitte testi, vaid TSL elemendi "requiredFiles" väljale ligi? Kas tuleb ülevalt koguaeg kaasas kanda?
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
                        "file_name" to PyStr(""), // TODO: Kuidas me saame siin mitte testi, vaid TSL elemendi "requiredFiles" väljale ligi? Kas tuleb ülevalt koguaeg kaasas kanda?
                        "function_name" to PyStr(""), // TODO: Funktsiooni nimi
                        "contains_check" to PyPair(
                            PyBool(test.containsLoop.mustContain),
                            PyBool(test.containsLoop.cannotContain)
                        ) // TODO: Kas teha eraldi PyTest?
                    )
                ).generatePyString()
            }
            is FunctionContainsKeywordTest -> {
                PyExecuteTest(
                    "function_contains_keyword_test",
                    mapOf(
                        "file_name" to PyStr(""), // TODO: Kuidas me saame siin mitte testi, vaid TSL elemendi "requiredFiles" väljale ligi? Kas tuleb ülevalt koguaeg kaasas kanda?
                        "function_name" to PyStr(""), // TODO: Funktsiooni nimi
                        "standard_output_checks" to PyStandardOutputChecks(listOf(test.standardOutputCheck)) // TODO: Kas on OK kasutada listi oma kuigi teame, et on ainult 1 element?
                    )
                ).generatePyString()
            }
            is FunctionContainsReturnTest -> {
                PyExecuteTest(
                    "function_contains_return_test",
                    mapOf(
                        "file_name" to PyStr(""), // TODO: Kuidas me saame siin mitte testi, vaid TSL elemendi "requiredFiles" väljale ligi? Kas tuleb ülevalt koguaeg kaasas kanda?
                        "function_name" to PyStr(""), // TODO: Funktsiooni nimi
                        "contains_check" to PyPair(
                            PyBool(test.containsReturn.mustContain),
                            PyBool(test.containsReturn.cannotContain)
                        ) // TODO: Kas teha eraldi PyTest?
                    )
                ).generatePyString()
            }
            is FunctionCallsFunctionTest -> {
                PyExecuteTest(
                    "function_calls_function_test",
                    mapOf(
                        "file_name" to PyStr(""), // TODO: Kuidas me saame siin mitte testi, vaid TSL elemendi "requiredFiles" väljale ligi? Kas tuleb ülevalt koguaeg kaasas kanda?
                        "function_name" to PyStr(""), // TODO: Funktsiooni nimi
                        "standard_output_checks" to PyStandardOutputChecksLong(listOf(test.standardOutputCheck))// TODO: Kas on OK kasutada listi oma kuigi teame, et on ainult 1 element?
                    )
                ).generatePyString()
            }
            is FunctionIsRecursiveTest -> {
                PyExecuteTest(
                    "function_is_recursive_test",
                    mapOf(
                        "file_name" to PyStr(""), // TODO: Kuidas me saame siin mitte testi, vaid TSL elemendi "requiredFiles" väljale ligi? Kas tuleb ülevalt koguaeg kaasas kanda?
                        "function_name" to PyStr(""), // TODO: Funktsiooni nimi
                        "contains_check" to PyPair(
                            PyBool(test.isRecursive.mustBeRecursive),
                            PyBool(test.isRecursive.cannotBeRecursive)
                        ) // TODO: Kas teha eraldi PyTest?
                    )
                ).generatePyString()
            }
            is FunctionDefinesFunctionTest -> {
                PyExecuteTest(
                    "function_defines_function_test",
                    mapOf(
                        "file_name" to PyStr(""), // TODO: Kuidas me saame siin mitte testi, vaid TSL elemendi "requiredFiles" väljale ligi? Kas tuleb ülevalt koguaeg kaasas kanda?
                        "function_name" to PyStr(""), // TODO: Funktsiooni nimi
                        "standard_output_checks" to PyStandardOutputChecksLong(listOf(test.standardOutputCheck))// TODO: Kas on OK kasutada listi oma kuigi teame, et on ainult 1 element?
                    )
                ).generatePyString()
            }
            is FunctionImportsModuleTest -> {
                PyExecuteTest(
                    "function_imports_module_test",
                    mapOf(
                        "file_name" to PyStr(""), // TODO: Kuidas me saame siin mitte testi, vaid TSL elemendi "requiredFiles" väljale ligi? Kas tuleb ülevalt koguaeg kaasas kanda?
                        "function_name" to PyStr(""), // TODO: Funktsiooni nimi
                        "standard_output_checks" to PyStandardOutputChecksLong(listOf(test.standardOutputCheck))// TODO: Kas on OK kasutada listi oma kuigi teame, et on ainult 1 element?
                    )
                ).generatePyString()
            }
            is FunctionContainsTryExceptTest -> {
                PyExecuteTest(
                    "function_contains_try_except_test",
                    mapOf(
                        "file_name" to PyStr(""), // TODO: Kuidas me saame siin mitte testi, vaid TSL elemendi "requiredFiles" väljale ligi? Kas tuleb ülevalt koguaeg kaasas kanda?
                        "function_name" to PyStr(""), // TODO: Funktsiooni nimi
                        "contains_check" to PyPair(
                            PyBool(test.containsTryExcept.mustContain),
                            PyBool(test.containsTryExcept.cannotContain)
                        ) // TODO: Kas teha eraldi PyTest?
                    )
                ).generatePyString()
            }
            is FunctionUsesOnlyLocalVarsTest -> {
                PyExecuteTest(
                    "function_uses_only_local_vars_test",
                    mapOf(
                        "file_name" to PyStr(""), // TODO: Kuidas me saame siin mitte testi, vaid TSL elemendi "requiredFiles" väljale ligi? Kas tuleb ülevalt koguaeg kaasas kanda?
                        "function_name" to PyStr(""), // TODO: Funktsiooni nimi
                        "contains_check" to PyPair(
                            PyBool(test.containsLocalVars.mustContain),
                            PyBool(test.containsLocalVars.cannotContain)
                        ) // TODO: Kas teha eraldi PyTest?
                    )
                ).generatePyString()
            }
            is ProgramExecutionTest -> {
                PyExecuteTest(
                    "program_execution_test",
                    mapOf(
                        "file_name" to PyStr(""), // TODO: Kuidas me saame siin mitte testi, vaid TSL elemendi "requiredFiles" väljale ligi? Kas tuleb ülevalt koguaeg kaasas kanda?
                        "standard_input_data" to PyStr(test.standardInputData),
                        "input_files" to test.inputFiles?.map { PyPair(PyStr(it.fileName), PyStr(it.fileContent)) }
                            ?.let { PyList(it) },
                        "standard_output_checks" to PyStandardOutputChecks(test.standardOutputChecks),
                        "output_file_checks" to PyOutputTests(test.outputFileChecks),
                        "exception_check" to PyPair(
                            PyBool(test.exceptionCheck?.mustThrowException),
                            PyBool(test.exceptionCheck?.cannotThrowException)
                        ) // TODO: Kas teha eraldi PyTest?
                    )
                ).generatePyString()
            }
            is ProgramContainsTryExceptTest -> {
                PyExecuteTest(
                    "program_contains_try_except_test",
                    mapOf(
                        "file_name" to PyStr(""), // TODO: Kuidas me saame siin mitte testi, vaid TSL elemendi "requiredFiles" väljale ligi? Kas tuleb ülevalt koguaeg kaasas kanda?
                        "contains_check" to PyPair(
                            PyBool(test.programContainsTryExcept.mustContain),
                            PyBool(test.programContainsTryExcept.cannotContain)
                        ) // TODO: Kas teha eraldi PyTest?
                    )
                ).generatePyString()
            }
            is ProgramCallsPrintTest -> {
                PyExecuteTest(
                    "program_calls_print_test",
                    mapOf(
                        "file_name" to PyStr(""), // TODO: Kuidas me saame siin mitte testi, vaid TSL elemendi "requiredFiles" väljale ligi? Kas tuleb ülevalt koguaeg kaasas kanda?
                        "contains_check" to PyPair(
                            PyBool(test.programCallsPrint.mustContain),
                            PyBool(test.programCallsPrint.cannotContain)
                        ) // TODO: Kas teha eraldi PyTest?
                    )
                ).generatePyString()
            }
            is ProgramContainsLoopTest -> {
                PyExecuteTest(
                    "program_contains_loop_test",
                    mapOf(
                        "file_name" to PyStr(""), // TODO: Kuidas me saame siin mitte testi, vaid TSL elemendi "requiredFiles" väljale ligi? Kas tuleb ülevalt koguaeg kaasas kanda?
                        "contains_check" to PyPair(
                            PyBool(test.programContainsLoop.mustContain),
                            PyBool(test.programContainsLoop.cannotContain)
                        ) // TODO: Kas teha eraldi PyTest?
                    )
                ).generatePyString()
            }
            is ProgramImportsModuleTest -> {
                PyExecuteTest(
                    "program_imports_module_test",
                    mapOf(
                        "file_name" to PyStr(""), // TODO: Kuidas me saame siin mitte testi, vaid TSL elemendi "requiredFiles" väljale ligi? Kas tuleb ülevalt koguaeg kaasas kanda?
                        "standard_output_checks" to PyStandardOutputChecksLong(listOf(test.standardOutputCheck)) // TODO: Kas on OK kasutada listi oma kuigi teame, et on ainult 1 element?
                    )
                ).generatePyString()
            }
            is ProgramContainsKeywordTest -> {
                PyExecuteTest(
                    "program_contains_keyword_test",
                    mapOf(
                        "file_name" to PyStr(""), // TODO: Kuidas me saame siin mitte testi, vaid TSL elemendi "requiredFiles" väljale ligi? Kas tuleb ülevalt koguaeg kaasas kanda?
                        "standard_output_checks" to PyStandardOutputChecks(listOf(test.standardOutputCheck)) // TODO: Kas on OK kasutada listi oma kuigi teame, et on ainult 1 element?
                    )
                ).generatePyString()
            }
            is ProgramCallsFunctionTest -> {
                PyExecuteTest(
                    "program_calls_function_test",
                    mapOf(
                        "file_name" to PyStr(""), // TODO: Kuidas me saame siin mitte testi, vaid TSL elemendi "requiredFiles" väljale ligi? Kas tuleb ülevalt koguaeg kaasas kanda?
                        "standard_output_checks" to PyStandardOutputChecksLong(listOf(test.standardOutputCheck)) // TODO: Kas on OK kasutada listi oma kuigi teame, et on ainult 1 element?
                    )
                ).generatePyString()
            }
            is ProgramDefinesFunctionTest -> {
                PyExecuteTest(
                    "program_defines_function_test",
                    mapOf(
                        "file_name" to PyStr(""), // TODO: Kuidas me saame siin mitte testi, vaid TSL elemendi "requiredFiles" väljale ligi? Kas tuleb ülevalt koguaeg kaasas kanda?
                        "standard_output_checks" to PyStandardOutputChecksLong(listOf(test.standardOutputCheck)) // TODO: Kas on OK kasutada listi oma kuigi teame, et on ainult 1 element?
                    )
                ).generatePyString()
            }

            else -> "Unknown Test"
        }
    }
}