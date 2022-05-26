package com.example.demo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("function_params_count_correct")
data class FunctionParamsCountCorrect(
    val numberOfParams: Int
) : Test()

@Serializable
@SerialName("function_imports_module")
data class FunctionImportsModule(
    val moduleName: String
) : Test()

@Serializable
@SerialName("function_imports_any_module")
data class FunctionImportsAnyModule(
    val defaultValue: String? = null
) : Test()

@Serializable
@SerialName("function_defines_function")
data class FunctionDefinesFunction(
    val functionName: String
) : Test()

@Serializable
@SerialName("function_defines_any_function")
data class FunctionDefinesAnyFunction(
    val defaultValue: String? = null
) : Test()

@Serializable
@SerialName("function_contains_loop")
data class FunctionContainsLoop(
    val defaultValue: String? = null
) : Test()

@Serializable
@SerialName("function_calls_function")
data class FunctionCallsFunction(
    val functionName: String
) : Test()

@Serializable
@SerialName("function_calls_function_from_set")
data class FunctionCallsFunctionFromSet(
    val functionsList: List<String>
) : Test()

@Serializable
@SerialName("function_calls_print")
data class FunctionCallsPrint(
    val defaultValue: String? = null
) : Test()

@Serializable
@SerialName("function_is_recursive")
data class FunctionIsRecursive(
    val defaultValue: String? = null
) : Test()

@Serializable
@SerialName("function_contains_return")
data class FunctionContainsReturn(
    val defaultValue: String? = null
) : Test()

@Serializable
@SerialName("function_input_count_correct")
data class FunctionInputCountCorrect(
    val defaultValue: String? = null
) : Test()

@Serializable
@SerialName("function_return_type_correct")
data class FunctionReturnTypeCorrect(
    val expectedReturnType: String
) : Test()

@Serializable
@SerialName("function_return_value_correct")
data class FunctionReturnValueCorrect(
    val defaultValue: String? = null
) : Test()

@Serializable
@SerialName("function_raised_exception")
data class FunctionRaisedException(
    val defaultValue: String? = null
) : Test()

@Serializable
@SerialName("function_returned")
data class FunctionReturned(
    val defaultValue: String? = null
) : Test()

