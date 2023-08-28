package pages.exercise.editor.tsl

import Icons
import Str
import components.ToastThing
import components.form.SelectComp
import debug
import kotlinx.coroutines.await
import libheaders.MCollapsibleInstance
import libheaders.Materialize
import libheaders.open
import pages.exercise.editor.tsl.tests.TSLFunctionCallTest
import pages.exercise.editor.tsl.tests.TSLPlaceholderTest
import pages.exercise.editor.tsl.tests.TSLProgramExecutionTest
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
        is FunctionCallsFunctionTest -> TODO()
        is FunctionCallsPrintTest -> TODO()
        is FunctionContainsKeywordTest -> TODO()
        is FunctionContainsLoopTest -> TODO()
        is FunctionContainsReturnTest -> TODO()
        is FunctionContainsTryExceptTest -> TODO()
        is FunctionDefinesFunctionTest -> TODO()
        is FunctionExecutionTest -> TestType.FUNCTION_EXECUTION
        is FunctionImportsModuleTest -> TODO()
        is FunctionIsPureTest -> TODO()
        is FunctionIsRecursiveTest -> TODO()
        is ProgramExecutionTest -> TestType.PROGRAM_EXECUTION
        is ProgramCallsFunctionTest -> TODO()
        is ProgramCallsPrintTest -> TODO()
        is ProgramContainsKeywordTest -> TODO()
        is ProgramContainsLoopTest -> TODO()
        is ProgramContainsTryExceptTest -> TODO()
        is ProgramDefinesFunctionTest -> TODO()
        is ProgramImportsModuleTest -> TODO()
        is ClassCallsClassTest -> TODO()
        is ClassDefinesFunctionTest -> TODO()
        is ClassFunctionCallsFunctionTest -> TODO()
        is ClassImportsModuleTest -> TODO()
        is ClassInstanceTest -> TODO()
        is ProgramCallsClassFunctionTest -> TODO()
        is ProgramCallsClassTest -> TODO()
        is ProgramDefinesClassTest -> TODO()
        is ProgramDefinesSubclassTest -> TODO()
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

        }

    enum class TestType(val optionName: String, val defaultTestTitle: String) {
        PLACEHOLDER("-", "Uus test"),
        PROGRAM_EXECUTION("Programmi väljund", "Programmi väljundi test"),
        FUNCTION_EXECUTION("Funktsiooni tagastus", "Funktsiooni tagastuse test"),
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