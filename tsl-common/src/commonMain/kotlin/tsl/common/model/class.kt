package tsl.common.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


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

    override fun copyTest(newId: Long) = copy(id = newId)
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

    override fun copyTest(newId: Long) = copy(id = newId)
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

    override fun copyTest(newId: Long) = copy(id = newId)
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

    override fun copyTest(newId: Long) = copy(id = newId)
}

@Serializable
@SerialName("class_instance_test")
data class ClassInstanceTest(
    override val id: Long,
    val className: String,
    val classInstanceChecks: List<ClassInstanceCheck> = emptyList(),
    val outputFileChecks: List<OutputFileCheck> = emptyList(),
    val genericChecks: List<GenericCheck> = emptyList(),
    val createObject: String
) : Test() {
    override fun getDefaultName(): String {
        return "Klassi isendi loomise test"
    }

    override fun copyTest(newId: Long) = copy(id = newId)
}

@Serializable
@SerialName("class_is_subclass_test")
data class ClassIsSubClassTest(
        override val id: Long,
        val className: String,
        val genericCheck: GenericCheckLong
) : Test() {
    override fun getDefaultName(): String {
        return "Klassi on teise klassi alamklass"
    }

    override fun copyTest(newId: Long) = copy(id = newId)
}

@Serializable
@SerialName("class_is_parent_class_test")
data class ClassIsParentClassTest(
        override val id: Long,
        val className: String,
        val genericCheck: GenericCheckLong
) : Test() {
    override fun getDefaultName(): String {
        return "Klassi on teise klassi ülamklass"
    }

    override fun copyTest(newId: Long) = copy(id = newId)
}
@Serializable
@SerialName("class_calls_class_function_test")
data class ClassCallsClassFunctionTest(
        override val id: Long,
        val className: String,
        val genericCheck: GenericCheckLong
) : Test() {
    override fun getDefaultName(): String {
        return "Klass kutsub välja klassi funktsiooni"
    }

    override fun copyTest(newId: Long) = copy(id = newId)
}
@Serializable
@SerialName("class_contains_keyword_test")
data class ClassContainsKeywordTest(
        override val id: Long,
        val className: String,
        val genericCheck: GenericCheck,
) : Test() {
    override fun getDefaultName(): String {
        return "Klass sisaldab märksõna"
    }

    override fun copyTest(newId: Long) = copy(id = newId)
}
@Serializable
@SerialName("class_contains_phrase_test")
data class ClassContainsPhraseTest(
        override val id: Long,
        val className: String,
        val genericCheck: GenericCheck,
) : Test() {
    override fun getDefaultName(): String {
        return "Klass sisaldab fraasi"
    }

    override fun copyTest(newId: Long) = copy(id = newId)
}