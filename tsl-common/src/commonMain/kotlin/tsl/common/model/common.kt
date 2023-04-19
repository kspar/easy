package tsl.common.model

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

    // TODO: convert into field?
    abstract fun getDefaultName(): String

    val points: Double = 1.0
    val name: String? = null
    val inputs: String? = null // TODO: Kaspar, kas selle jätame?
    val passedNext: Long? = null
    val failedNext: Long? = null
    val visibleToUser: Boolean? = null
    // val programOutput: String? = null
    //output_files
    //prog_inputs
    //return_type
    //return_value
    //fun_arguments
    //fun_param_count
}

enum class CheckType {
    ALL_OF_THESE, ANY_OF_THESE, MISSING_AT_LEAST_ONE_OF_THESE, NONE_OF_THESE
}

enum class CheckTypeLong {
    ALL_OF_THESE, ANY_OF_THESE, ANY, NONE_OF_THESE, MISSING_AT_LEAST_ONE_OF_THESE, NONE
}

@Serializable
class FileData(
    val fileName: String,
    val fileContent: String
)

// TODO: Kui on nt vaja kontrollida nimede olemasolu, siis kuidas me kontrollime,
//  et näiteks 3 nime eksisteerib väljundis, aga 2 nime mitte?

@Serializable
data class GenericCheck(
    val checkType: CheckType,
    val nothingElse: Boolean? = null,
    val expectedValue: List<String>, // The field to be checked.
    val considerElementsOrder: Boolean? = false,
    override val beforeMessage: String,
    override val passedMessage: String,
    override val failedMessage: String
) : Check()

@Serializable
data class GenericCheckLong(
    val checkType: CheckTypeLong,
    val nothingElse: Boolean? = null,
    val expectedValue: List<String>, // The field to be checked.
    override val beforeMessage: String,
    override val passedMessage: String,
    override val failedMessage: String
) : Check()

@Serializable
data class OutputFileCheck(
    val fileName: String,
    val checkType: CheckType,
    val nothingElse: Boolean? = null,
    val expectedValue: List<String>, // The field to be checked.
    val considerElementsOrder: Boolean? = false,
    override val beforeMessage: String,
    override val passedMessage: String,
    override val failedMessage: String
) : Check()

@Serializable
class ExceptionCheck(
    val mustNotThrowException: Boolean,
    override val beforeMessage: String,
    override val passedMessage: String,
    override val failedMessage: String
) : Check()

@Serializable
class ContainsCheck(
    val mustNotContain: Boolean,
    override val beforeMessage: String,
    override val passedMessage: String,
    override val failedMessage: String
) : Check()

@Serializable
class RecursiveCheck(
    val mustNotBeRecursive: Boolean,
    override val beforeMessage: String,
    override val passedMessage: String,
    override val failedMessage: String
) : Check()

@Serializable
class CallsCheck(
    val mustNotCall: Boolean,
    override val beforeMessage: String,
    override val passedMessage: String,
    override val failedMessage: String
) : Check()