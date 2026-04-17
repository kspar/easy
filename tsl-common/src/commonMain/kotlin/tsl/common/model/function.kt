package tsl.common.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


enum class FunctionType {
    FUNCTION, METHOD
}

enum class FunctionProperty { PURE, RECURSIVE }


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
@SerialName("function_is_test")
data class FunctionIsTest(
    override val id: Long,
    val functionName: String,
    val functionProperty: FunctionProperty,
) : Test() {
    override fun getDefaultName(): String = when (functionProperty) {
        FunctionProperty.PURE -> "Funktsioon kasutab vaid lokaalseid muutujaid"
        FunctionProperty.RECURSIVE -> "Funktsioon on rekursiivne"
    }

    override fun copyTest(newId: Long) = copy(id = newId)
}
