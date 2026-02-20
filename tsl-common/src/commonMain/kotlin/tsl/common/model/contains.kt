package tsl.common.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class ContainsWhat {
    KEYWORD_NO_ARG, // Python märksõna (try, except, finally, if, elif, else, break, continue, import, return, for, while)
    KEYWORD_WITH_PRECEDING_ARG, // Python märksõna (import, return)
    PHRASE, // Suvaline teksti string
}


@Serializable
@SerialName("contains_test")
data class ContainsTest(
    override val id: Long,
    val scope: Scope,
    val containsWhat: ContainsWhat,
    val containsWhatArg: String? = null,
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

        val containsText = when (containsWhat) {
            ContainsWhat.KEYWORD_NO_ARG -> "reserveeritud võtmesõna"
            ContainsWhat.KEYWORD_WITH_PRECEDING_ARG -> "reserveeritud võtmesõna koos argumendiga $containsWhatArg"
            ContainsWhat.PHRASE -> "sõne"
        }

        return "$scopeText otsib $containsText"
    }


    override fun copyTest(newId: Long) = copy(id = newId)

}










