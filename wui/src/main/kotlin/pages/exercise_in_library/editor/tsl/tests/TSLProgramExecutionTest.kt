package pages.exercise_in_library.editor.tsl.tests

import pages.exercise_in_library.editor.tsl.TSLTestComponent
import pages.exercise_in_library.editor.tsl.sections.TSLDataChecksSection
import pages.exercise_in_library.editor.tsl.sections.TSLInputFilesSection
import pages.exercise_in_library.editor.tsl.sections.TSLStdInSection
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import template
import tsl.common.model.FileData
import tsl.common.model.ProgramExecutionTest
import tsl.common.model.Test

class TSLProgramExecutionTest(
    private val initialModel: ProgramExecutionTest?,
    private val onUpdate: () -> Unit,
    private val onValidChanged: () -> Unit,
    parent: Component,
    dstId: String,
) : TSLTestComponent(parent, dstId) {

    private val testId = initialModel?.id ?: IdGenerator.nextLongId()

    private val stdInSection =
        TSLStdInSection(initialModel?.standardInputData.orEmpty(), onUpdate, onValidChanged, this)

    private val inputFilesSection =
        TSLInputFilesSection(
            initialModel?.inputFiles.orEmpty().associate { it.fileName to it.fileContent }.toMutableMap(),
            onUpdate, onValidChanged, this
        )

    private val dataChecks =
        TSLDataChecksSection(initialModel?.genericChecks.orEmpty().toMutableList(), onUpdate, onValidChanged, this)

    override val children: List<Component>
        get() = listOf(stdInSection, inputFilesSection, dataChecks)

    override fun render() = template(
        """
            <ez-tsl-section-title>Sisendandmed</ez-tsl-section-title>
            $stdInSection
            $inputFilesSection
            
            <ez-tsl-section-title>Kontrollid</ez-tsl-section-title>
            $dataChecks
        """.trimIndent()
    )

    override fun getTSLModel(): Test {
        return ProgramExecutionTest(
            testId,
            stdInSection.getInputs(),
            genericChecks = dataChecks.updateAndGetChecks(),
            inputFiles = inputFilesSection.updateAndGetFiles().map {
                FileData(it.key, it.value)
            },
        )
    }

    override fun setEditable(nowEditable: Boolean) {
        stdInSection.setEditable(nowEditable)
        dataChecks.setEditable(nowEditable)
        inputFilesSection.setEditable(nowEditable)
    }

    override fun isValid() =
        stdInSection.isValid() &&
                dataChecks.isValid() &&
                inputFilesSection.isValid()
}
