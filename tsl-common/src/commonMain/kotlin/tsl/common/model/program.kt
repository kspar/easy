package tsl.common.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import tsl.common.model.*


@Serializable
@SerialName("program_execution_test")
data class ProgramExecutionTest(
    override val id: Long,
    val standardInputData: List<String> = emptyList(),
    val inputFiles: List<FileData> = emptyList(),
    val genericChecks: List<GenericCheck> = emptyList(),
    val outputFileChecks: List<OutputFileCheck> = emptyList(),
    val exceptionCheck: ExceptionCheck? = null,
) : Test() {
    override fun getDefaultName(): String {
        return "Programmi käivituse test"
    }
}

@Serializable
@SerialName("program_contains_try_except_test")
data class ProgramContainsTryExceptTest(
    override val id: Long,
    val programContainsTryExcept: ContainsCheck
) : Test() {
    override fun getDefaultName(): String {
        return "Programm sisaldab 'try/except' plokki"
    }
}

@Serializable
@SerialName("program_calls_print_test")
data class ProgramCallsPrintTest(
    override val id: Long,
    val programCallsPrint: CallsCheck
) : Test() {
    override fun getDefaultName(): String {
        return "Programm kutsub välja 'print' käsu"
    }
}

@Serializable
@SerialName("program_contains_loop_test")
data class ProgramContainsLoopTest(
    override val id: Long,
    val programContainsLoop: ContainsCheck
) : Test() {
    override fun getDefaultName(): String {
        return "Programm sisaldab tsüklit"
    }
}

@Serializable
@SerialName("program_imports_module_test")
data class ProgramImportsModuleTest(
    override val id: Long,
    val genericCheck: GenericCheckLong
) : Test() {
    override fun getDefaultName(): String {
        return "Programm impordib mooduli"
    }
}

@Serializable
@SerialName("program_contains_keyword_test")
data class ProgramContainsKeywordTest(
    override val id: Long,
    val genericCheck: GenericCheck
) : Test() {
    override fun getDefaultName(): String {
        return "Programm sisaldab märksõna"
    }
}

@Serializable
@SerialName("program_calls_function_test")
data class ProgramCallsFunctionTest(
    override val id: Long,
    val genericCheck: GenericCheckLong
) : Test() {
    override fun getDefaultName(): String {
        return "Programm kutsub välja funktsiooni"
    }
}

@Serializable
@SerialName("program_defines_function_test")
data class ProgramDefinesFunctionTest(
    override val id: Long,
    val genericCheck: GenericCheckLong
) : Test() {
    override fun getDefaultName(): String {
        return "Programm defineerib funktsiooni"
    }
}

@Serializable
@SerialName("program_defines_class_test")
data class ProgramDefinesClassTest(
    override val id: Long,
    val genericCheck: GenericCheckLong
) : Test() {
    override fun getDefaultName(): String {
        return "Programm defineerib klassi"
    }
}

@Serializable
@SerialName("program_defines_subclass_test")
data class ProgramDefinesSubclassTest(
    override val id: Long,
    val className: String,
    val superClass: String,
    val beforeMessage: String,
    val passedMessage: String,
    val failedMessage: String
) : Test() {
    override fun getDefaultName(): String {
        return "Programm defineerib alamklassi"
    }
}

@Serializable
@SerialName("program_calls_class_test")
data class ProgramCallsClassTest(
    override val id: Long,
    val genericCheck: GenericCheckLong
) : Test() {
    override fun getDefaultName(): String {
        return "Programm kutsub välja klassi"
    }
}

@Serializable
@SerialName("program_calls_class_function_test")
data class ProgramCallsClassFunctionTest(
    override val id: Long,
    val genericCheck: GenericCheckLong
) : Test() {
    override fun getDefaultName(): String {
        return "Programm kutsub välja klassi funktsiooni"
    }
}