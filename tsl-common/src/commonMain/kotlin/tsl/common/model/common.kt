package tsl.common.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

interface TSLModel

val TSLFormat = Json {
    encodeDefaults = true
    prettyPrint = true
}

@Serializable
data class TSL(
    val language: String = "python3",
    val validateFiles: Boolean,
    // The first file in the requiredFiles list is always the file to be executed.
    // We can expect from UI that there is always at least one file in the list.
    // TODO: Execution-tüüpi testide puhul pole vaja faili nime, alati kasutatakse peafaili listist.
    // TODO: Static tüüpi testide puhul oleks tarvis vajadusel määrata faili nimi, vastasel juhul otsitakse üle kõigi.
    val requiredFiles: List<String>,
    val tslVersion: String,
    val tests: List<Test>
) : TSLModel

@Serializable
abstract class Check : TSLModel {
    abstract val beforeMessage: String
    abstract val passedMessage: String
    abstract val failedMessage: String
}

@Serializable
sealed class Test : TSLModel {
    abstract val id: Long
    open var name: String? = null

    // TODO: to int
    open val pointsWeight: Double = 1.0
    open val visibleToUser: Boolean = true

    // TODO: convert into field?
    abstract fun getDefaultName(): String

    val inputs: String? = null // TODO: Kaspar, kas selle jätame?
    val passedNext: Long? = null
    val failedNext: Long? = null
    // val programOutput: String? = null
    //output_files
    //prog_inputs
    //return_type
    //return_value
    //fun_arguments
    //fun_param_count

    abstract fun copyTest(newId: Long): Test
}

@Serializable
@SerialName("placeholder_test")
data class PlaceholderTest(
    override val id: Long,
) : Test() {
    override fun getDefaultName() = "Uus test"
    override fun copyTest(newId: Long) = copy(id = newId)
}

enum class CheckType {
    ALL_OF_THESE, ANY_OF_THESE, MISSING_AT_LEAST_ONE_OF_THESE, NONE_OF_THESE
}

enum class CheckTypeLong {
    ALL_OF_THESE, ANY_OF_THESE, ANY, NONE_OF_THESE, MISSING_AT_LEAST_ONE_OF_THESE, NONE
}

enum class DataCategory {
    CONTAINS_LINES, CONTAINS_NUMBERS, CONTAINS_STRINGS, EQUALS
}

enum class OutputCategory {
    ALL_IO, ALL_OUTPUT, LAST_OUTPUT, OUTPUT_NUMBER_0, OUTPUT_NUMBER_1, OUTPUT_NUMBER_2, OUTPUT_NUMBER_3, OUTPUT_NUMBER_4, OUTPUT_NUMBER_5, OUTPUT_NUMBER_6, OUTPUT_NUMBER_7, OUTPUT_NUMBER_8, OUTPUT_NUMBER_9
}

enum class Scope(val value: String) {
    PROGRAM("program"),
    FUNCTION("function"),
    CLASS("class"),
    MAIN_PROGRAM("main_program")

}

@Serializable
data class FileData(
    val fileName: String,
    val fileContent: String
)

@Serializable
data class FieldData(
    val fieldName: String,
    val fieldContent: String
)

// TODO: Kui on nt vaja kontrollida nimede olemasolu, siis kuidas me kontrollime,
//  et näiteks 3 nime eksisteerib väljundis, aga 2 nime mitte?

// TODO: rename to DataCheck or ValueCheck?
@Serializable
data class GenericCheck(
    var id: Long,
    val checkType: CheckType,
    val nothingElse: Boolean? = null,
    val expectedValue: List<String>,
    val elementsOrdered: Boolean? = false,
    val dataCategory: DataCategory = DataCategory.EQUALS,
    val outputCategory: OutputCategory = OutputCategory.ALL_IO,
    val ignoreCase: Boolean? = false,
    override val beforeMessage: String,
    override val passedMessage: String,
    override val failedMessage: String
) : Check()

@Serializable
data class GenericCheckLong(
    val checkType: CheckTypeLong,
    val nothingElse: Boolean? = null,
    val expectedValue: List<String>,
    val dataCategory: DataCategory = DataCategory.EQUALS,
    val ignoreCase: Boolean? = false,
    override val beforeMessage: String,
    override val passedMessage: String,
    override val failedMessage: String
) : Check()

@Serializable
data class OutputFileCheck(
    val fileName: String,
    val checkType: CheckType,
    val nothingElse: Boolean? = null,
    val expectedValue: List<String>,
    val elementsOrdered: Boolean? = false,
    val dataCategory: DataCategory = DataCategory.EQUALS,
    val ignoreCase: Boolean? = false,
    override val beforeMessage: String,
    override val passedMessage: String,
    override val failedMessage: String
) : Check()

@Serializable
data class ExceptionCheck(
    val mustNotThrowException: Boolean,
    override val beforeMessage: String,
    override val passedMessage: String,
    override val failedMessage: String
) : Check()

@Serializable
data class ReturnValueCheck(
    val returnValue: String,
    override val beforeMessage: String,
    override val passedMessage: String,
    override val failedMessage: String
) : Check()

@Serializable
data class ParamValueCheck(
    val paramNumber: Int,
    val expectedValue: String,
    override val beforeMessage: String,
    override val passedMessage: String,
    override val failedMessage: String
) : Check()

@Serializable
data class ClassInstanceCheck(
    val fieldsFinal: List<FieldData> = emptyList(),
    val checkName: Boolean,
    val checkValue: Boolean,
    val nothingElse: Boolean,
    override val beforeMessage: String,
    override val passedMessage: String,
    override val failedMessage: String
) : Check()


@Serializable
data class ContainsCheck(
    val mustNotContain: Boolean,
    override val beforeMessage: String,
    override val passedMessage: String,
    override val failedMessage: String
) : Check()

@Serializable
data class RecursiveCheck(
    val mustNotBeRecursive: Boolean,
    override val beforeMessage: String,
    override val passedMessage: String,
    override val failedMessage: String
) : Check()

@Serializable
data class CallsCheck(
    val mustNotCall: Boolean,
    override val beforeMessage: String,
    override val passedMessage: String,
    override val failedMessage: String
) : Check()