package com.example.demo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("program_imports_module")
data class ProgramImportsModule(
    val moduleName: String
) : Test()

@Serializable
@SerialName("program_imports_module_from_set")
data class ProgramImportsModuleFromSet(
    val moduleNames: List<String>
) : Test()

@Serializable
@SerialName("program_imports_any_module")
data class ProgramImportsAnyModule(
    val defaultValue: String? = null
) : Test()

@Serializable
@SerialName("program_defines_function")
data class ProgramDefinesFunction(
    val functionName: String
) : Test()

@Serializable
@SerialName("program_defines_any_function")
data class ProgramDefinesAnyFunction(
    val defaultValue: String? = null
) : Test()

@Serializable
@SerialName("program_contains_loop")
data class ProgramContainsLoop(
    val defaultValue: String? = null
) : Test()

@Serializable
@SerialName("program_calls_function")
data class ProgramCallsFunction(
    val functionName: String
) : Test()

@Serializable
@SerialName("program_calls_function_from_set")
data class ProgramCallsFunctionFromSet(
    val functionsList: List<String>
) : Test()

@Serializable
@SerialName("program_calls_print")
data class ProgramCallsPrint(
    val defaultValue: String? = null
) : Test()

@Serializable
@SerialName("program_input_count_correct")
data class ProgramInputCountCorrect(
    val defaultValue: String? = null
) : Test()

@Serializable
@SerialName("program_raised_exception")
data class ProgramRaisedException(
    val defaultValue: String? = null
) : Test()

@Serializable
@SerialName("program_contains_try_except")
data class ProgramContainsTryExcept(
    val defaultValue: String? = null
) : Test()

@Serializable
@SerialName("program_output_correct")
data class ProgramOutputCorrect(
    val defaultValue: String? = null
) : Test()

@Serializable
@SerialName("program_output_file_correct")
data class ProgramOutputFileCorrect(
    val defaultValue: String? = null
) : Test()