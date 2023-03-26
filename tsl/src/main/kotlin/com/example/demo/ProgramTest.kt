package com.example.demo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("program_execution_test")
data class ProgramExecutionTest(
    val standardInputData: List<String>? = null,
    val inputFiles: List<FileData>? = null,
    val genericChecks: List<GenericCheck>? = null,
    val outputFileChecks: List<OutputFileCheck>? = null,
    val exceptionCheck: ExceptionCheck? = null,
) : Test() {
    override fun getDefaultName(): String {
        return "Programmi käivituse test"
    }
}

@Serializable
@SerialName("program_contains_try_except_test")
data class ProgramContainsTryExceptTest(
    val programContainsTryExcept: ContainsCheck
) : Test() {
    override fun getDefaultName(): String {
        return "Programm sisaldab 'try/except' plokki"
    }
}

@Serializable
@SerialName("program_calls_print_test")
data class ProgramCallsPrintTest(
    val programCallsPrint: CallsCheck
) : Test() {
    override fun getDefaultName(): String {
        return "Programm kutsub välja 'print' käsu"
    }
}

@Serializable
@SerialName("program_contains_loop_test")
data class ProgramContainsLoopTest(
    val programContainsLoop: ContainsCheck
) : Test() {
    override fun getDefaultName(): String {
        return "Programm sisaldab tsüklit"
    }
}

@Serializable
@SerialName("program_imports_module_test")
data class ProgramImportsModuleTest(
    val genericCheck: GenericCheckLong
) : Test() {
    override fun getDefaultName(): String {
        return "Programm impordib mooduli"
    }
}

@Serializable
@SerialName("program_contains_keyword_test")
data class ProgramContainsKeywordTest(
    val genericCheck: GenericCheck
) : Test() {
    override fun getDefaultName(): String {
        return "Programm sisaldab märksõna"
    }
}

@Serializable
@SerialName("program_calls_function_test")
data class ProgramCallsFunctionTest(
    val genericCheck: GenericCheckLong
) : Test() {
    override fun getDefaultName(): String {
        return "Programm kutsub välja funktsiooni"
    }
}

@Serializable
@SerialName("program_defines_function_test")
data class ProgramDefinesFunctionTest(
    val genericCheck: GenericCheckLong
) : Test() {
    override fun getDefaultName(): String {
        return "Programm defineerib funktsiooni"
    }
}