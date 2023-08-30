package pages.exercise_in_library.editor.tsl.tests

import pages.exercise_in_library.editor.tsl.TSLTestComponent
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import tsl.common.model.PlaceholderTest
import tsl.common.model.Test

class TSLNotImplementedTest(
    private val initialModel: Test?,
    parent: Component,
    dstId: String,
) : TSLTestComponent(parent, dstId) {

    private val testId = initialModel?.id ?: IdGenerator.nextLongId()

    override fun getTSLModel() = initialModel ?: PlaceholderTest(testId)

    override fun setEditable(nowEditable: Boolean) {}

    override fun isValid() = true
}

