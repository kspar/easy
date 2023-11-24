package pages.exercise_in_library.editor.tsl

import Icons
import components.form.ButtonComp
import kotlinx.dom.addClass
import kotlinx.dom.removeClass
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.getElemBySelector
import show
import template
import translation.Str
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

    private var isEditable: Boolean = false

    override val children: List<Component>
        get() = listOf(testsList, addTestBtn)

    override fun create() = doInPromise {
        testsList = TSLTestsListComp(tests, onUpdate, onValidChanged, this)
        addTestBtn = ButtonComp(ButtonComp.Type.PRIMARY, Str.tslAddTest, Icons.add, ::addTest, parent = this)
    }

    override fun render() = template(
        """
            <ez-tsl-tests id="${testsList.dstId}" class='${if (isEditable) "editable" else ""}' 
                style="display: flex; flex-direction: column;"></ez-tsl-tests>
            <ez-tsl-add-test id="${addTestBtn.dstId}"></ez-tsl-add-test>
        """.trimIndent()
    )

    fun getComposedTests() = testsList.updateAndGetTests()

    fun getOpenTests() = testsList.getOpenTests()

    fun openTests(testIds: List<Long>) = testsList.openTests(testIds)

    private suspend fun addTest() {
        testsList.addTest()
        onUpdate()
    }

    fun setEditable(nowEditable: Boolean) {
        isEditable = nowEditable
        getElemBySelector("ez-tsl-tests").let {
            if (nowEditable) it.addClass("editable")
            else it.removeClass("editable")
        }
        addTestBtn.show(nowEditable)
        testsList.setEditable(nowEditable)
    }

    fun isValid() = testsList.isValid()
}

