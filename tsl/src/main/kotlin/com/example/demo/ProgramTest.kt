package com.example.demo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("program_execution_test") // Tulemuse test
data class ProgramExecutionTest(
    val standardInputData: String? = null,
    val inputFiles: List<FileData>? = null, // Sama nii input kui output data-le
    val standardOutputChecks: List<StandardOutputCheck>? = null,
    val outputFileChecks: List<OutputFileCheck>? = null,
    val exceptionCheck: ExceptionCheck? = null,
) : Test()

// TODO: Programmi täitmise kontroll = 1 test,
// mis tähendab, et punktid lähevad terve "programmi täitmise kontrolli" ploki kohta.
// TODO: "tühi test" on võimalik, kõik väljad on "null"-id ja peaks ka Tiibade poole pealt võimaldama

@Serializable
@SerialName("program_contains_try_except_test")
data class ProgramContainsTryExceptTest(
    val programContainsTryExcept: ContainsCheck
) : Test()

@Serializable
@SerialName("program_calls_print_test")
data class ProgramCallsPrintTest(
    val programCallsPrint: ContainsCheck
) : Test()

@Serializable
@SerialName("program_contains_loop_test")
data class ProgramContainsLoopTest(
    val programContainsLoop: ContainsCheck
) : Test()

@Serializable
@SerialName("program_imports_module_test")
data class ProgramImportsModuleTest(
    val standardOutputCheck: StandardOutputCheckLong
) : Test()

@Serializable
@SerialName("program_contains_keyword_test")
data class ProgramContainsKeywordTest(
    val standardOutputCheck: StandardOutputCheck
) : Test()

@Serializable
@SerialName("program_calls_function_test")
data class ProgramCallsFunctionTest(
    val standardOutputCheck: StandardOutputCheckLong
) : Test()

@Serializable
@SerialName("program_defines_function_test")
data class ProgramDefinesFunctionTest(
    val standardOutputCheck: StandardOutputCheckLong
) : Test()