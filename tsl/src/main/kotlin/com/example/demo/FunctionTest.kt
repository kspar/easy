package com.example.demo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// TODO: Refactor: Later remove 'functionName' and create a Function class that has it in it by deafult.

@Serializable
@SerialName("function_execution_test") // Funktsiooni käivituse test
data class FunctionExecutionTest(
    val functionName: String,
    val arguments: List<String>? = null,
    val standardInputData: List<String>? = null,
    val inputFiles: List<FileData>? = null, // Sama nii input kui output data-le
    val returnValue: String? = null,
    val standardOutputChecks: List<StandardOutputCheck>? = null,
    val outputFileChecks: List<OutputFileCheck>? = null
) : Test()

@Serializable
@SerialName("function_contains_loop_test")
data class FunctionContainsLoopTest(
    val functionName: String,
    val containsLoop: ContainsCheck
) : Test()

@Serializable
@SerialName("function_contains_keyword_test")
data class FunctionContainsKeywordTest(
    val functionName: String,
    val standardOutputCheck: StandardOutputCheck,
) : Test()

@Serializable
@SerialName("function_contains_return_test")
data class FunctionContainsReturnTest(
    val functionName: String,
    val containsReturn: ContainsCheck
) : Test()

@Serializable
@SerialName("function_calls_function_test")
data class FunctionCallsFunctionTest(
    val functionName: String,
    val standardOutputCheck: StandardOutputCheckLong
) : Test()

@Serializable
@SerialName("function_calls_print_test")
data class FunctionCallsPrintTest(
    val functionName: String,
    val callsCheck: CallsCheck
) : Test()

@Serializable
@SerialName("function_is_recursive_test")
data class FunctionIsRecursiveTest(
    val functionName: String,
    val isRecursive: RecursiveCheck
) : Test()

@Serializable
@SerialName("function_defines_function_test")
data class FunctionDefinesFunctionTest(
    val functionName: String,
    val standardOutputCheck: StandardOutputCheckLong
) : Test()

@Serializable
@SerialName("function_imports_module_test")
data class FunctionImportsModuleTest(
    val functionName: String,
    val standardOutputCheck: StandardOutputCheckLong
) : Test()

@Serializable
@SerialName("function_contains_try_except_test")
data class FunctionContainsTryExceptTest(
    val functionName: String,
    val containsTryExcept: ContainsCheck
) : Test()

@Serializable
@SerialName("function_uses_only_local_vars_test")
data class FunctionUsesOnlyLocalVarsTest(
    val functionName: String,
    val containsLocalVars: ContainsCheck
) : Test()
