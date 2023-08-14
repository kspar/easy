package pages.exercise.editor.tsl.tests

import pages.exercise.editor.tsl.TSLTestComponent
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import tsl.common.model.PlaceholderTest

class TSLPlaceholderTest(
    initialModel: PlaceholderTest?,
    parent: Component,
    dstId: String,
) : TSLTestComponent(parent, dstId) {

    private val testId = initialModel?.id ?: IdGenerator.nextLongId()

    override fun getTSLModel() = PlaceholderTest(testId)

    override fun setEditable(nowEditable: Boolean) {}

    override fun isValid() = true
}
