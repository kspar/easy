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
    val tooManyArgumentsProvidedErrorMsg: String = "Funktsioon võtab sisendiks vale arvu argumente"
) : Test() {
    override fun getDefaultName(): String {
        return "Funktsiooni käivitus"
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
