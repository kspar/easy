package tsl.common.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import tsl.common.model.*


enum class FunctionType {
    FUNCTION, METHOD
}

@Serializable
@SerialName("function_execution_test")
data class FunctionExecutionTest(
    override val id: Long,
    val functionName: String,
    val functionType: FunctionType,
    val createObject: String? = null,
    val arguments: List<String> = emptyList(),
    val standardInputData: List<String> = emptyList(),
    val inputFiles: List<FileData> = emptyList(),
    val returnValue: String? = null,
    val genericChecks: List<GenericCheck> = emptyList(),
    val outputFileChecks: List<OutputFileCheck> = emptyList()
) : Test() {
    override fun getDefaultName(): String {
        return "Funktsiooni käivitus"
    }
}

@Serializable
@SerialName("function_contains_loop_test")
data class FunctionContainsLoopTest(
    override val id: Long,
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
    override val id: Long,
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
    override val id: Long,
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
    override val id: Long,
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
    override val id: Long,
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
    override val id: Long,
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
    override val id: Long,
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
    override val id: Long,
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
    override val id: Long,
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
    override val id: Long,
    val functionName: String,
    val containsLocalVars: ContainsCheck
) : Test() {
    override fun getDefaultName(): String {
        return "Funktsioon kasutab vaid lokaalseid muutujaid"
    }
}
