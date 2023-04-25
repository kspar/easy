package pages.exercise.editor.tsl

import components.PageTabsComp
import debug
import kotlinx.coroutines.await
import kotlinx.serialization.encodeToString
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import template
import tsl.common.model.TSL
import tsl.common.model.TSLFormat
import tsl.common.model.Test

class TSLRootComp(
    parent: Component
) : Component(parent) {

    private val compilerFeedback = TSLCompilerFeedbackComp(parent = this)

    val tabs = PageTabsComp(
        PageTabsComp.Type.SUBPAGE,
        listOf(
            PageTabsComp.Tab("Testid", compProvider = { TSLTabComposeComp(emptyList(), ::updateTsl, it) }),
            PageTabsComp.Tab(
                "TSL",
                compProvider = { TSLTabTslComp(::updateCompose, it) },
                onActivate = { tslComp.refreshEditor() }),
            PageTabsComp.Tab("Genereeritud skriptid", compProvider = { TSLTabScriptsComp(it) }),
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

    private suspend fun updateCompose(newTsl: String?) {
        val model = if (newTsl != null) {
            try {
                TSLFormat.decodeFromString(TSL.serializer(), newTsl)
            } catch (e: Exception) {
                compilerFeedback.setFeedback(e.message)
                return
            }
        } else {
            createTSLModel(emptyList())
        }

        compilerFeedback.setFeedback(null)

        val openTests = composeComp.getOpenTests()
        composeComp.tests = model.tests
        composeComp.createAndBuild().await()
        composeComp.openTests(openTests)
    }

    private fun updateTsl() {
        debug { "Updating editor with tests: ${composeComp.getComposedTests().map { it.id }}" }
        val model = createTSLModel(composeComp.getComposedTests())
        val tslStr = TSLFormat.encodeToString(model)
        tslComp.setTsl(tslStr)

        compileTsl(tslStr)
    }

    private fun compileTsl(tslStr: String) = doInPromise {
        // TODO: waiting for yaml -> json
//        val scripts = TSLDAO.compile(tslStr).await()
//        scriptsComp.setScripts(scripts.scripts ?: emptyList())
    }

    private fun createTSLModel(tests: List<Test>): TSL {
        return TSL(
            validateFiles = true,
            tslVersion = "1.0",
            requiredFiles = emptyList(),
            tests = tests
        )
    }
}