package tsl.common.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import tsl.common.model.*


@Serializable
@SerialName("class_imports_module_test")
data class ClassImportsModuleTest(
    override val id: Long,
    val className: String,
    val genericCheck: GenericCheckLong
) : Test() {
    override fun getDefaultName(): String {
        return "Klass impordib mooduli"
    }
}


@Serializable
@SerialName("class_defines_function_test")
data class ClassDefinesFunctionTest(
    override val id: Long,
    val className: String,
    val genericCheck: GenericCheckLong
) : Test() {
    override fun getDefaultName(): String {
        return "Klass defineerib funktsiooni"
    }
}


@Serializable
@SerialName("class_calls_class_test")
data class ClassCallsClassTest(
    override val id: Long,
    val className: String,
    val genericCheck: GenericCheckLong
) : Test() {
    override fun getDefaultName(): String {
        return "Klass kutsub välja teise klassi"
    }
}

@Serializable
@SerialName("class_function_calls_function_test")
data class ClassFunctionCallsFunctionTest(
    override val id: Long,
    val className: String,
    val classFunctionName: String,
    val genericCheck: GenericCheckLong
) : Test() {
    override fun getDefaultName(): String {
        return "Klassi funktsioon kutsub välja funktsiooni"
    }
}

@Serializable
@SerialName("class_instance_test")
data class ClassInstanceTest(
    override val id: Long,
    val className: String,
    val classInstanceChecks: List<ClassInstanceCheck> = emptyList(),
    val createObject: String
) : Test() {
    override fun getDefaultName(): String {
        return "Klassi isendi loomise test"
    }
}