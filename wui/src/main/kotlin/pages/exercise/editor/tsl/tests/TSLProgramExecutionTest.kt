package pages.exercise.editor.tsl.tests

import pages.exercise.editor.tsl.TSLTestComponent
import pages.exercise.editor.tsl.sections.TSLStdInSection
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.plainDstStr
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

    override val children: List<Component>
        get() = listOf(stdInSection)

    override fun render() = plainDstStr(children.map { it.dstId })

    override fun getTSLModel(): Test {
        return ProgramExecutionTest(
            testId,
            stdInSection.getInputs()
        )
    }

    override fun setEditable(nowEditable: Boolean) {
        stdInSection.setEditable(nowEditable)
    }

    override fun isValid() = stdInSection.isValid()
}
