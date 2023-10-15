package tsl.common.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


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
    val genericChecks: List<GenericCheck> = emptyList(),
    val returnValueCheck: ReturnValueCheck? = null,
    val paramValueChecks: List<ParamValueCheck> = emptyList(),
    val outputFileChecks: List<OutputFileCheck> = emptyList(),
    val outOfInputsErrorMsg: String = "Programm küsis rohkem sisendeid kui testil oli anda",
    val functionNotDefinedErrorMsg: String = "Funktsioon ei ole defineeritud",
    val wrongNumberOfArgumentsProvidedErrorMsg: String = "Funktsioon võtab sisendiks vale arvu argumente"
) : Test() {
    override fun getDefaultName(): String {
        return "Funktsiooni käivitus"
    }

    override fun copyTest(newId: Long) = copy(id = newId)
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

    override fun copyTest(newId: Long) = copy(id = newId)
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

    override fun copyTest(newId: Long) = copy(id = newId)
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

    override fun copyTest(newId: Long) = copy(id = newId)
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

    override fun copyTest(newId: Long) = copy(id = newId)
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

    override fun copyTest(newId: Long) = copy(id = newId)
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

    override fun copyTest(newId: Long) = copy(id = newId)
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

    override fun copyTest(newId: Long) = copy(id = newId)
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

    override fun copyTest(newId: Long) = copy(id = newId)
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

    override fun copyTest(newId: Long) = copy(id = newId)
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

    override fun copyTest(newId: Long) = copy(id = newId)
}
