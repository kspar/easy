package pages.exercise.editor.tsl.tests

import pages.exercise.editor.tsl.TSLTestComponent
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import tsl.common.model.FunctionExecutionTest
import tsl.common.model.Test

class TSLFunctionCallTest(
    private val initialModel: FunctionExecutionTest?,
    private val onUpdate: () -> Unit,
    parent: Component,
    dstId: String,
) : TSLTestComponent(parent, dstId) {

    val testId = initialModel?.id ?: IdGenerator.nextLongId()

    override val children: List<Component>
        get() = listOf()

    override fun render() = "Fun call"

    override fun getTSLModel(): Test {
        return FunctionExecutionTest(
            testId,
            "fun",
            listOf("42"),
            returnValue = "foo"
        )
    }

}