package tsl.common.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("mainProgram_calls_function_test")
data class MainProgramCallsFunctionTest(
        override val id: Long,
        val genericCheck: GenericCheckLong
) : Test() {
    override fun getDefaultName(): String {
        return "Põhi programm kutsub välja funktsiooni"
    }

    override fun copyTest(newId: Long) = copy(id = newId)
}

@Serializable
@SerialName("mainProgram_calls_class_test")
data class MainProgramCallsClassTest(
        override val id: Long,
        val genericCheck: GenericCheckLong
) : Test() {
    override fun getDefaultName(): String {
        return "Põhi programm kutsub välja klassi"
    }

    override fun copyTest(newId: Long) = copy(id = newId)
}

@Serializable
@SerialName("mainProgram_calls_class_function_test")
data class MainProgramCallsClassFunctionTest(
        override val id: Long,
        val genericCheck: GenericCheckLong
) : Test() {
    override fun getDefaultName(): String {
        return "Põhi programm kutsub välja klassi funktsiooni"
    }

    override fun copyTest(newId: Long) = copy(id = newId)
}

@Serializable
@SerialName("mainProgram_contains_keyword_test")
data class MainProgramContainsKeywordTest(
        override val id: Long,
        val genericCheck: GenericCheck
) : Test() {
    override fun getDefaultName(): String {
        return "Põhiprogramm sisaldab märksõna"
    }

    override fun copyTest(newId: Long) = copy(id = newId)
}

@Serializable
@SerialName("mainProgram_contains_phrase_test")
data class MainProgramContainsPhraseTest(
        override val id: Long,
        val genericCheck: GenericCheck
) : Test() {
    override fun getDefaultName(): String {
        return "Põhiprogramm sisaldab fraasi"
    }

    override fun copyTest(newId: Long) = copy(id = newId)
}
@Serializable
@SerialName("mainProgram_contains_loop_test")
data class MainProgramContainsLoopTest(
        override val id: Long,
        val programContainsLoop: ContainsCheck
) : Test() {
    override fun getDefaultName(): String {
        return "Põhirogramm sisaldab tsüklit"
    }

    override fun copyTest(newId: Long) = copy(id = newId)
}
