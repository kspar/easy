package tsl.common.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


enum class DefinitionCheckType { FUNCTION, CLASS }

@Serializable
@SerialName("definition_test")
data class DefinitionTest(
    override val id: Long,

    val scopeType: Scope,

    val className: String? = null,
    val functionName: String? = null,

    // The name being defined:
    // - FUNCTION: function name
    // - CLASS: class name
    val definitionCheckValue: String,

    // Optional inheritance: only for DefinitionCheckType CLASS
    val superClassName: String? = null,

    val definitionCheckType: DefinitionCheckType,

    val genericCheck: GenericCheckLong
) : Test() {
    override fun getDefaultName(): String {

        val scopeText = when (scopeType) {
            Scope.PROGRAM -> "Programm"
            Scope.MAIN_PROGRAM -> "Põhiprogramm"
            Scope.FUNCTION -> "Funktsioon"
            Scope.CLASS -> "Klass"
        }


        val targetText = when (definitionCheckType) {
            DefinitionCheckType.FUNCTION -> "funktsiooni $definitionCheckValue"
            DefinitionCheckType.CLASS ->
                if (superClassName.isNullOrBlank()) "klassi $definitionCheckValue"
                else "$superClassName alamklassi $definitionCheckValue"
        }

        return "$scopeText defineerib $targetText"

    }

    override fun copyTest(newId: Long) = copy(id = newId)

}

