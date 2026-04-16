package tsl.common.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


enum class TargetType(val value: String) {
    FUNCTION("function"),
    CLASS("class"),
    CLASS_FUNCTION("class_function")
}


@Serializable
@SerialName("calls_test")
data class CallsTest(
    override val id: Long,
    val scope: Scope,
    val targetType: TargetType,
    val functionName: String? = null,
    val className: String? = null,
    val targetClassName: String? = null,
    val genericCheck: GenericCheckLong
) : Test() {
    override fun getDefaultName(): String {
        val scopeText = when (scope) {
            Scope.PROGRAM -> "Programm"
            Scope.MAIN_PROGRAM -> "Põhi programm"
            Scope.FUNCTION -> "Funktsioon"
            Scope.CLASS -> "Klass"
        }

        val targetTypeText = when (targetType) {
            TargetType.FUNCTION -> "funktsiooni"
            TargetType.CLASS -> "klassi"
            TargetType.CLASS_FUNCTION -> "klassi funktsiooni"
        }
        return "$scopeText kutsub välja $targetTypeText"
    }


    override fun copyTest(newId: Long) = copy(id = newId)

}


