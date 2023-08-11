package pages.exercise.editor.tsl

import Icons
import components.form.ButtonComp
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.doInPromise
import show
import template
import tsl.common.model.Test


class TSLTabComposeComp(
    var tests: List<Test>,
    private val onUpdate: () -> Unit,
    private val onValidChanged: () -> Unit,
    parent: Component,
    dstId: String = IdGenerator.nextId()
) : Component(parent, dstId) {

    private lateinit var testsList: TSLTestsListComp
    private lateinit var addTestBtn: ButtonComp

    override val children: List<Component>
        get() = listOf(testsList, addTestBtn)

    override fun create() = doInPromise {
        testsList = TSLTestsListComp(tests, onUpdate, onValidChanged, this)
        addTestBtn = ButtonComp(ButtonComp.Type.PRIMARY, "Lisa test", Icons.add, ::addTest, parent = this)
    }

    override fun render() = template(
        """
            <ez-tsl-tests id="{{tests}}" style="display: flex; flex-direction: column;"></ez-tsl-tests>
            <ez-tsl-add-test id="{{btn}}"></ez-tsl-add-test>
        """.trimIndent(),
        "tests" to testsList.dstId,
        "btn" to addTestBtn.dstId
    )

    fun getComposedTests() = testsList.getTests()

    fun getOpenTests() = testsList.getOpenTests()

    fun openTests(testIds: List<Long>) = testsList.openTests(testIds)

    private suspend fun addTest() {
        testsList.addTest()
        onUpdate()
    }

    fun setEditable(nowEditable: Boolean) {
        addTestBtn.show(nowEditable)
        testsList.setEditable(nowEditable)
    }

    fun isValid() = testsList.isValid()
}

