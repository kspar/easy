package pages.exercise.editor.tsl

import Icons
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
import show
import template
import tsl.common.model.*

class TSLTestComp(
    private val initialModel: Test,
    private val onUpdate: () -> Unit,
    private val onValidChanged: () -> Unit,
    parent: Component
) : Component(parent) {

    private var activeTitle = initialModel.name ?: getTestTypeFromModel(initialModel).defaultTestTitle

    private val testType = SelectComp(
        "Testi tüüp", TestType.values().map {
            SelectComp.Option(it.optionName, it.name, getTestTypeFromModel(initialModel) == it)
        },
        onOptionChange = { changeTestType(it!!) }, parent = this
    )

    private val contentDst = IdGenerator.nextId()
    private lateinit var content: TSLTestComponent

    private lateinit var collapsible: MCollapsibleInstance

    var isOpen = false
        private set

    override val children: List<Component>
        get() = listOf(testType, content)

    override fun create() = doInPromise {
        content = createContentComp(getTestTypeFromModel(initialModel), initialModel)
    }

    override fun render() = template(
        """
            <ul class="ez-collapsible collapsible">
                <li>
                    <div class="collapsible-header">
                        <ez-tsl-test-header-left>
                            <ez-collapsible-icon>{{{titleIcon}}}</ez-collapsible-icon>
                            <ez-collapsible-title>{{title}}</ez-collapsible-title>
                        </ez-tsl-test-header-left>
                        <ez-tsl-test-header-right>
                            <ez-icon-action ez-tsl-test-menu id='ez-tsl-test-menu-{{testDst}}' title="{{menuLabel}}" class="waves-effect dropdown-trigger icon-med" tabindex="0" data-target="ez-tsl-test-{{testDst}}">{{{menuIcon}}}</ez-icon-action>
                        </ez-tsl-test-header-right>
                    </div>
                    <div class="collapsible-body">
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
    """.trimIndent(),
        "title" to activeTitle,
        "testTypeDst" to testType.dstId,
        "testContentDst" to contentDst,
        "testDst" to dstId,
        "actions" to TestAction.values().map {
            mapOf(
                "id" to it.name,
                "iconHtml" to it.icon,
                "text" to it.title,
            )
        },
        "titleIcon" to Icons.robot,
        "menuLabel" to "Muuda...",
        "menuIcon" to Icons.dotsVertical,
    )

    override fun postRender() {
        getElemById(dstId).getElemBySelector("[ez-tsl-test-menu]").onVanillaClick(false) {
            // Stop click from propagating to collapsible header
            it.stopPropagation()
        }

        TestAction.values().forEach { action ->
            getElemById(dstId).getElemBySelector("[ez-action=\"${action.name}\"]").onVanillaClick(false) {
                it.stopPropagation()
                debug { "Action ${action.name} on test $activeTitle" }
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

    fun getTestModel() = content.getTSLModel()

    fun open() = collapsible.open()

    fun setEditable(nowEditable: Boolean) {
        // 3-dot menu
        getElemById("ez-tsl-test-menu-$dstId").show(nowEditable)
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

    enum class TestAction(val title: String, val icon: String, val action: () -> Unit) {
        RENAME("Muuda pealkirja", Icons.edit, {}),
        MOVE("Liiguta", Icons.reorder, {}),
        DELETE("Kustuta", Icons.delete, {}),
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