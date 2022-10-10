package com.example.demo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("function_execution_test") // Funktsiooni käivituse test
data class FunctionExecutionTest(
    val functionName: String,
    val arguments: String? = null,
    val standardInputData: String? = null,
    val inputFiles: List<FileData>? = null, // Sama nii input kui output data-le
    val returnValue: String? = null,
    val standardOutputChecks: List<StandardOutputCheck>? = null,
    val outputFileChecks: List<OutputFileCheck>? = null
) : Test()

// TODO: Kas igal funktsiooniga seotud testil peaks ka funktsiooni nimi olema? Äkki saaks teha "FunctionTest" alamklassi?
@Serializable
@SerialName("function_contains_loop_test")
data class FunctionContainsLoopTest(
    val containsLoop: ContainsCheck
) : Test()

@Serializable
@SerialName("function_contains_keyword_test")
data class FunctionContainsKeywordTest(
    val standardOutputCheck: StandardOutputCheck
) : Test()

@Serializable
@SerialName("function_contains_return_test")
data class FunctionContainsReturnTest(
    val containsReturn: ContainsCheck
) : Test()

@Serializable
@SerialName("function_calls_function_test")
data class FunctionCallsFunctionTest(
    val standardOutputCheck: StandardOutputCheckLong
) : Test()

@Serializable
@SerialName("function_is_recursive_test")
data class FunctionIsRecursiveTest(
    val isRecursive: RecursiveCheck
) : Test()

@Serializable
@SerialName("function_defines_function_test")
data class FunctionDefinesFunctionTest(
    val standardOutputCheck: StandardOutputCheckLong
) : Test()

@Serializable
@SerialName("function_imports_module_test")
data class FunctionImportsModuleTest(
    val standardOutputCheck: StandardOutputCheckLong
) : Test()

@Serializable
@SerialName("function_contains_try_except_test")
data class FunctionContainsTryExceptTest(
    val containsTryExcept: ContainsCheck
) : Test()

@Serializable
@SerialName("function_uses_only_local_vars_test")
data class FunctionUsesOnlyLocalVarsTest(
    val containsLocalVars: ContainsCheck
) : Test()
