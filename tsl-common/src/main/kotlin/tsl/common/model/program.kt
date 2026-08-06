package tsl.common.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
@SerialName("program_execution_test")
data class ProgramExecutionTest(
    override val id: Long,
    val standardInputData: List<String> = emptyList(),
    val inputFiles: List<FileData> = emptyList(),
    val genericChecks: List<GenericCheck> = emptyList(),
    val outputFileChecks: List<OutputFileCheck> = emptyList(),
    var exceptionCheck: ExceptionCheck? = null,
) : Test() {
    override fun getDefaultName(): String {
        return "Programmi käivituse test"
    }

    override fun copyTest(newId: Long) = copy(id = newId)
}


