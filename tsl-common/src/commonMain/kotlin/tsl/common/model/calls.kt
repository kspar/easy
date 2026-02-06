package tsl.common.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class Scope(val value: String) {
    PROGRAM("program"),
    FUNCTION("function"),
    CLASS("class"),
    MAIN_PROGRAM("main_program")

}

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
    val scopeTargetName: Scope? = null,  // Required for FUNCTION and CLASS scopes
    val genericCheck: GenericCheckLong
) : Test() {
    override fun getDefaultName(): String {
        val scopeText = when (scope) {
            Scope.PROGRAM -> "Programm"
            Scope.MAIN_PROGRAM -> "Põhi programm"
            Scope.FUNCTION -> "Funktsioon"
            Scope.CLASS -> "Klass"
        }

        val targetText = when (targetType) {
            TargetType.FUNCTION -> "funktsiooni"
            TargetType.CLASS -> "klassi"
            TargetType.CLASS_FUNCTION -> "klassi funktsiooni"
        }
        return "$scopeText kutsub välja $targetText"
    }


    override fun copyTest(newId: Long) = copy(id = newId)

}













