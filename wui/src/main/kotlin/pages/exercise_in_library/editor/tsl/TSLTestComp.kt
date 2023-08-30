package pages.exercise_in_library.editor.tsl

import Icons
import Str
import components.ToastThing
import components.form.SelectComp
import debug
import kotlinx.coroutines.await
import libheaders.MCollapsibleInstance
import libheaders.Materialize
import libheaders.open
import pages.exercise_in_library.editor.tsl.tests.TSLFunctionCallTest
import pages.exercise_in_library.editor.tsl.tests.TSLNotImplementedTest
import pages.exercise_in_library.editor.tsl.tests.TSLPlaceholderTest
import pages.exercise_in_library.editor.tsl.tests.TSLProgramExecutionTest
import rip.kspar.ezspa.*
import template
import tsl.common.model.*

class TSLTestComp(
    val idx: Int,
    private val initialModel: Test,
    private val onUpdate: () -> Unit,
    private val onValidChanged: () -> Unit,
    private val onDelete: suspend (Test) -> Unit,
    private val onRestore: suspend (Test, Int, Boolean) -> Unit,
    private val onReorder: suspend (Test) -> Unit,
    parent: Component
) : Component(parent) {

    private val editTitleModal = TSLEditTitleModalComp(::changeTitle, this)

    private val testType = SelectComp(
        Str.testType(), TestType.values().map {
            SelectComp.Option(it.optionName, it.name, getTestTypeFromModel(initialModel) == it)
        },
        onOptionChange = { changeTestType(it!!) }, parent = this
    )

    private val contentDst = IdGenerator.nextId()
    private lateinit var content: TSLTestComponent

    private lateinit var collapsible: MCollapsibleInstance

    private var activeTitle = initialModel.name ?: getTestTypeFromModel(initialModel).defaultTestTitle

    var isOpen = false
        private set


    data class TestAction(val title: String, val icon: String, val action: suspend () -> Unit) {
        val id: String = IdGenerator.nextId()
    }

    private val testActions = listOf(
        TestAction(Str.doMove(), Icons.reorder) {
            onReorder(getTestModel())
        },
        TestAction(Str.doDelete(), Icons.delete) {
            val activeModel = getTestModel()
            debug { "Delete test ${activeModel.id}" }
            onDelete(activeModel)
            ToastThing(Str.deleted(), ToastThing.Action(Str.doRestore(), {
                debug { "Restore test ${activeModel.id}" }
                onRestore(activeModel, idx, isOpen)
            }))
        },
    )

    override val children: List<Component>
        get() = listOf(editTitleModal, testType, content)

    override fun create() = doInPromise {
        content = createContentComp(getTestTypeFromModel(initialModel), initialModel)
    }

    override fun render() = template(
        """
            <ul class="ez-collapsible collapsible" style='margin: 0 0 1rem 0; border: 1px solid var(--ez-border);'>
                <li>
                    <div class="collapsible-header" style='border: 0; min-height: 5.5rem; /* to keep height when showing/hiding 3-dot menu */'>
                        <ez-tsl-test-header-left>
                            <ez-collapsible-icon>{{{titleIcon}}}</ez-collapsible-icon>
                            <ez-collapsible-title>{{title}}</ez-collapsible-title>
                            <ez-tsl-edit-title>
                                <ez-icon-action title="{{editLabel}}" class="waves-effect" tabindex="0">{{{editIcon}}}</ez-icon-action>
                            </ez-tsl-edit-title>
                        </ez-tsl-test-header-left>
                        <ez-tsl-test-header-right>
                            <ez-icon-action ez-tsl-test-menu title="{{menuLabel}}" class="waves-effect dropdown-trigger icon-med" tabindex="0" data-target="ez-tsl-test-{{testDst}}">{{{menuIcon}}}</ez-icon-action>
                        </ez-tsl-test-header-right>
                    </div>
                    <div class="collapsible-body" style='border-bottom: none; border-top: 2px solid var(--ez-attr-key);'>
                        <ez-dst id="{{testTypeDst}}"></ez-dst>
                        <ez-dst id="{{testContentDst}}"></ez-dst>
                    </div>
                </li>
            </ul>
            <!-- Menu structure -->
            <ul id="ez-tsl-test-{{testDst}}" class="dropdown-content">
                {{#actions}}
                    <li><span ez-action="{{id}}">{{{iconHtml}}}{{text}}</span></li>
                {{/actions}}
            </ul>
            <ez-dst id='{{editTitleDst}}'></ez-dst>
    """.trimIndent(),
        "title" to activeTitle,
        "editLabel" to Str.doEditTitle(),
        "testTypeDst" to testType.dstId,
        "testContentDst" to contentDst,
        "testDst" to dstId,
        "actions" to testActions.map {
            mapOf(
                "id" to it.id,
                "iconHtml" to it.icon,
                "text" to it.title,
            )
        },
        "titleIcon" to Icons.robot,
        "editIcon" to Icons.edit,
        "menuLabel" to Str.doChange() + "...",
        "menuIcon" to Icons.dotsVertical,
        "editTitleDst" to editTitleModal.dstId,
    )

    override fun postRender() {
        getElemBySelector("#$dstId ez-tsl-edit-title").onVanillaClick(false) {
            // Stop click from propagating to collapsible header
            it.stopPropagation()
            editTitleModal.title = activeTitle
            editTitleModal.openWithClosePromise().await()
        }

        getElemById(dstId).getElemBySelector("[ez-tsl-test-menu]").onVanillaClick(false) {
            // Stop click from propagating to collapsible header
            it.stopPropagation()
        }

        testActions.forEach { action ->
            getElemById(dstId).getElemBySelector("[ez-action=\"${action.id}\"]").onVanillaClick(false) {
                it.stopPropagation()
                debug { "Action ${action.title} on test $activeTitle" }
                action.action()
            }
        }

        collapsible = Materialize.Collapsible.init(
            getElemById(dstId).getElemsByClass("ez-collapsible").single(),
            objOf(
                "onOpenStart" to { isOpen = true },
                "onCloseStart" to { isOpen = false },
            )
        )

        Materialize.Dropdown.init(
            getElemById(dstId).getElemBySelector("[ez-tsl-test-menu]"),
            objOf("constrainWidth" to false, "coverTrigger" to false)
        )
    }

    fun getTestModel() = content.getTSLModel().also { it.name = activeTitle }

    fun open() = collapsible.open()

    fun setEditable(nowEditable: Boolean) {
        testType.isDisabled = !nowEditable
        testType.rebuild()
        content.setEditable(nowEditable)
    }

    fun isValid() = content.isValid()

    private suspend fun changeTestType(newTypeId: String) {
        debug { "Test type changed to: $newTypeId" }

        val newType = TestType.valueOf(newTypeId)

        // Change title if previous one was default
        if (TestType.defaultTitles.contains(activeTitle)) {
            changeTitle(newType.defaultTestTitle)
        }

        content.destroy()
        content = createContentComp(newType, null).also {
            it.createAndBuild().await()
        }

        // Test type changed
        onUpdate()
    }

    private fun changeTitle(newTitle: String) {
        activeTitle = newTitle
        getElemById(dstId).getElemBySelector("ez-collapsible-title").textContent = newTitle
        onUpdate()
    }

    private fun getTestTypeFromModel(test: Test) = when (test) {
        is FunctionCallsFunctionTest -> TestType.NOT_IMPLEMENTED
        is FunctionCallsPrintTest -> TestType.NOT_IMPLEMENTED
        is FunctionContainsKeywordTest -> TestType.NOT_IMPLEMENTED
        is FunctionContainsLoopTest -> TestType.NOT_IMPLEMENTED
        is FunctionContainsReturnTest -> TestType.NOT_IMPLEMENTED
        is FunctionContainsTryExceptTest -> TestType.NOT_IMPLEMENTED
        is FunctionDefinesFunctionTest -> TestType.NOT_IMPLEMENTED
        is FunctionExecutionTest -> TestType.FUNCTION_EXECUTION
        is FunctionImportsModuleTest -> TestType.NOT_IMPLEMENTED
        is FunctionIsPureTest -> TestType.NOT_IMPLEMENTED
        is FunctionIsRecursiveTest -> TestType.NOT_IMPLEMENTED
        is ProgramExecutionTest -> TestType.PROGRAM_EXECUTION
        is ProgramCallsFunctionTest -> TestType.NOT_IMPLEMENTED
        is ProgramCallsPrintTest -> TestType.NOT_IMPLEMENTED
        is ProgramContainsKeywordTest -> TestType.NOT_IMPLEMENTED
        is ProgramContainsLoopTest -> TestType.NOT_IMPLEMENTED
        is ProgramContainsTryExceptTest -> TestType.NOT_IMPLEMENTED
        is ProgramDefinesFunctionTest -> TestType.NOT_IMPLEMENTED
        is ProgramImportsModuleTest -> TestType.NOT_IMPLEMENTED
        is ClassCallsClassTest -> TestType.NOT_IMPLEMENTED
        is ClassDefinesFunctionTest -> TestType.NOT_IMPLEMENTED
        is ClassFunctionCallsFunctionTest -> TestType.NOT_IMPLEMENTED
        is ClassImportsModuleTest -> TestType.NOT_IMPLEMENTED
        is ClassInstanceTest -> TestType.NOT_IMPLEMENTED
        is ProgramCallsClassFunctionTest -> TestType.NOT_IMPLEMENTED
        is ProgramCallsClassTest -> TestType.NOT_IMPLEMENTED
        is ProgramDefinesClassTest -> TestType.NOT_IMPLEMENTED
        is ProgramDefinesSubclassTest -> TestType.NOT_IMPLEMENTED
        is PlaceholderTest -> TestType.PLACEHOLDER
    }

    private suspend fun createContentComp(type: TestType, model: Test?): TSLTestComponent =
        // it might be better to show/hide content comps to preserve inputs
        when (type) {
            TestType.PROGRAM_EXECUTION ->
                TSLProgramExecutionTest(
                    model?.let { it as ProgramExecutionTest },
                    onUpdate,
                    onValidChanged,
                    this,
                    contentDst
                )

            TestType.FUNCTION_EXECUTION ->
                TSLFunctionCallTest(
                    model?.let { it as FunctionExecutionTest },
                    onUpdate,
                    onValidChanged,
                    this,
                    contentDst
                )

            TestType.PLACEHOLDER ->
                TSLPlaceholderTest(model?.let { it as PlaceholderTest }, this, contentDst)

            TestType.NOT_IMPLEMENTED ->
                TSLNotImplementedTest(model, this, contentDst)
        }

    enum class TestType(val optionName: String, val defaultTestTitle: String) {
        PLACEHOLDER("-", "Uus test"),
        PROGRAM_EXECUTION("Programmi väljund", "Programmi väljundi test"),
        FUNCTION_EXECUTION("Funktsiooni tagastus", "Funktsiooni tagastuse test"),

        NOT_IMPLEMENTED("Ära puutu!", "Kasutajaliideses implementeerimata test")
        ;

        companion object {
            val defaultTitles = TestType.values().map { it.defaultTestTitle }
        }
    }
}

abstract class TSLTestComponent(
    parent: Component?,
    dstId: String = IdGenerator.nextId()
) : Component(parent, dstId) {

    abstract fun getTSLModel(): Test

    abstract fun setEditable(nowEditable: Boolean)

    abstract fun isValid(): Boolean
}