package pages.exercise_in_library.editor.tsl

import components.PageTabsComp
import dao.TSLDAO
import debug
import kotlinx.coroutines.await
import kotlinx.serialization.encodeToString
import pages.exercise_in_library.editor.AutoassessEditorComp.Companion.TSL_META_FILENAME
import pages.exercise_in_library.editor.AutoassessEditorComp.Companion.TSL_SPEC_FILENAME_JSON
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.doInPromise
import template
import translation.Str
import tsl.common.model.TSL
import tsl.common.model.TSLFormat
import tsl.common.model.Test

class TSLRootComp(
    parent: Component,
    private val assets: Map<String, String>,
    private var isEditable: Boolean,
    private val onValidChanged: () -> Unit,
) : Component(parent) {

    enum class Tab(val id: String) { COMPOSE(IdGenerator.nextId()), SPEC(IdGenerator.nextId()), GENERATED(IdGenerator.nextId()) }

    private var isCompileValid = true

    private val compilerFeedback = TSLCompilerFeedbackComp(parent = this)

    private val initialTslSpecStr = assets[TSL_SPEC_FILENAME_JSON] ?: createEmptyTslSpec()

    val tabs = PageTabsComp(
        PageTabsComp.Type.SUBPAGE,
        listOf(
            PageTabsComp.Tab(
                Str.tslTestsTab,
                compProvider = { TSLTabComposeComp(emptyList(), ::updateTsl, onValidChanged, it) },
                id = Tab.COMPOSE.id
            ),
            PageTabsComp.Tab(
                Str.tslSpecTab,
                compProvider = { TSLTabTslComp(initialTslSpecStr, ::updateCompose, it) },
                id = Tab.SPEC.id
            ),
            PageTabsComp.Tab(
                Str.tslGeneratedTab,
                compProvider = { TSLTabScriptsComp(assets - TSL_SPEC_FILENAME_JSON, it) },
                id = Tab.GENERATED.id
            )
        ), parent = this
    )

    private var composeComp: TSLTabComposeComp = tabs.getTabComps()[0] as TSLTabComposeComp
    private val tslComp: TSLTabTslComp = tabs.getTabComps()[1] as TSLTabTslComp
    private val scriptsComp: TSLTabScriptsComp = tabs.getTabComps()[2] as TSLTabScriptsComp

    override val children: List<Component>
        get() = listOf(compilerFeedback, tabs)

    override fun render() = template(
        """
            <ez-dst id='{{feedbackDst}}'></ez-dst>
            <ez-tsl-root id='{{tabsDst}}'></ez-tsl-root>
        """.trimIndent(),
        "feedbackDst" to compilerFeedback.dstId,
        "tabsDst" to tabs.dstId,
    )

    override fun postChildrenBuilt() {
        doInPromise {
            updateCompose(initialTslSpecStr)
            setEditable(isEditable)
        }
    }

    fun getTslSpec() = mapOf(TSL_SPEC_FILENAME_JSON to tslComp.getTsl())

    suspend fun setEditable(nowEditable: Boolean) {
        isEditable = nowEditable
        composeComp.setEditable(nowEditable)
        tslComp.setEditable(nowEditable)
    }

    fun isValid() = isCompileValid && composeComp.isValid()

    fun getOpenTests() = composeComp.getOpenTests()

    fun openTests(testIds: List<Long>) = composeComp.openTests(testIds)

    fun getActiveTab(): Tab = Tab.values().single { it.id == tabs.getSelectedTab().id }

    fun setActiveTab(tab: Tab) = tabs.setSelectedTabById(tab.id)

    // Only check TSL spec changes, ignore compose
    override fun hasUnsavedChanges() = tslComp.getTsl() != initialTslSpecStr

    private suspend fun updateCompose(newTsl: String?) {
        val tslSpec = if (newTsl.isNullOrBlank()) {
            createEmptyTslSpec().also { tslComp.setTsl(it) }
        } else newTsl

        val model = try {
            TSLFormat.decodeFromString(TSL.serializer(), tslSpec)
        } catch (e: Exception) {
            compilerFeedback.setFeedback(e.message)
            setCompileValid(false)
            return
        }

        compilerFeedback.setFeedback(null)
        setCompileValid(true)

        val openTests = composeComp.getOpenTests()
        composeComp.tests = model.tests
        composeComp.createAndBuild().await()
        composeComp.openTests(openTests)

        if (isEditable)
            compileTsl(tslSpec)
    }

    private suspend fun updateTsl() {
        debug { "Updating editor with tests: ${composeComp.getComposedTests().map { it.id }}" }
        val model = createTSLModel(composeComp.getComposedTests())
        val tslStr = TSLFormat.encodeToString(model)
        tslComp.setTsl(tslStr)

        compileTsl(tslStr)
    }

    private fun compileTsl(tslStr: String) = doInPromise {
        val compileResult = TSLDAO.compile(tslStr, TSLDAO.FORMAT.JSON).await()

        compilerFeedback.setFeedback(compileResult.feedback)
        setCompileValid(compileResult.feedback == null)

        val metaStr = compileResult.meta?.let {
            """
                    Compiled at: ${it.timestamp.date.toUTCString()}
                    Compiler version: ${it.compiler_version}
                    Backend: ${it.backend_id} ${it.backend_version}
                """.trimIndent()
        }
        val metaScript = metaStr?.let { mapOf(TSL_META_FILENAME to it) }.orEmpty()

        scriptsComp.setScripts(
            compileResult.scripts?.associate { it.name to it.value }.orEmpty() + metaScript
        )
    }

    private fun setCompileValid(nowValid: Boolean) {
        isCompileValid = nowValid
        onValidChanged()
    }

    private fun createEmptyTslSpec() = TSLFormat.encodeToString(createTSLModel(emptyList()))

    private fun createTSLModel(tests: List<Test>): TSL {
        return TSL(
            validateFiles = true,
            tslVersion = "1.0",
            requiredFiles = listOf("lahendus.py"),
            tests = tests
        )
    }
}