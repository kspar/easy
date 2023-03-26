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
    val genericChecks: List<GenericCheck>? = null,
    val outputFileChecks: List<OutputFileCheck>? = null
) : Test() {
    override fun getDefaultName(): String {
        return "Funktsiooni käivitus"
    }
}

@Serializable
@SerialName("function_contains_loop_test")
data class FunctionContainsLoopTest(
    val functionName: String,
    val containsLoop: ContainsCheck
) : Test() {
    override fun getDefaultName(): String {
        return "Funktsioon sisaldab tsüklit"
    }
}

@Serializable
@SerialName("function_contains_keyword_test")
data class FunctionContainsKeywordTest(
    val functionName: String,
    val genericCheck: GenericCheck,
) : Test() {
    override fun getDefaultName(): String {
        return "Funktsioon sisaldab märksõna"
    }
}

@Serializable
@SerialName("function_contains_return_test")
data class FunctionContainsReturnTest(
    val functionName: String,
    val containsReturn: ContainsCheck
) : Test() {
    override fun getDefaultName(): String {
        return "Funktsioon sisaldab 'return' käsku"
    }
}

@Serializable
@SerialName("function_calls_function_test")
data class FunctionCallsFunctionTest(
    val functionName: String,
    val genericCheck: GenericCheckLong
) : Test() {
    override fun getDefaultName(): String {
        return "Funktsioon kutsub välja teist funktsiooni"
    }
}

@Serializable
@SerialName("function_calls_print_test")
data class FunctionCallsPrintTest(
    val functionName: String,
    val callsCheck: CallsCheck
) : Test() {
    override fun getDefaultName(): String {
        return "Funktsioon kutsub välja 'print' käsu"
    }
}

@Serializable
@SerialName("function_is_recursive_test")
data class FunctionIsRecursiveTest(
    val functionName: String,
    val isRecursive: RecursiveCheck
) : Test() {
    override fun getDefaultName(): String {
        return "Funktsioon on rekursiivne"
    }
}

@Serializable
@SerialName("function_defines_function_test")
data class FunctionDefinesFunctionTest(
    val functionName: String,
    val genericCheck: GenericCheckLong
) : Test() {
    override fun getDefaultName(): String {
        return "Funktsioon defineerib enda sees uue funktsiooni"
    }
}

@Serializable
@SerialName("function_imports_module_test")
data class FunctionImportsModuleTest(
    val functionName: String,
    val genericCheck: GenericCheckLong
) : Test() {
    override fun getDefaultName(): String {
        return "Funktsioon impordib mooduli"
    }
}

@Serializable
@SerialName("function_contains_try_except_test")
data class FunctionContainsTryExceptTest(
    val functionName: String,
    val containsTryExcept: ContainsCheck
) : Test() {
    override fun getDefaultName(): String {
        return "Funktsioon sisaldab 'try/except' plokki"
    }
}

@Serializable
@SerialName("function_is_pure_test")
data class FunctionIsPureTest(
    val functionName: String,
    val containsLocalVars: ContainsCheck
) : Test() {
    override fun getDefaultName(): String {
        return "Funktsioon kasutab vaid lokaalseid muutujaid"
    }
}
