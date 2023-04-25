package pages.exercise.editor

import pages.exercise.editor.tsl.TSLRootComp
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise

class AutoassessTSLEditorComp(
    private val evaluateScript: String,
    private val assets: Map<String, String>,
    startEditable: Boolean,
    parent: Component?,
) : AutoassessEditorComp(parent) {

    val tslRoot = TSLRootComp(this)

    private var isEditable = startEditable

    override val children: List<Component>
        get() = listOf(tslRoot)

    override fun create() = doInPromise {

    }

    override suspend fun setEditable(nowEditable: Boolean) {
        isEditable = nowEditable
        // TODO: disable all form elements?
    }

    override fun getEvalScript(): String {
        return ""
    }

    override fun getAssets(): Map<String, String> {
        return emptyMap()
    }

    override fun isValid() = true


    object ActiveView : AutoassessEditorComp.ActiveView

    override fun getActiveView() = ActiveView
    override fun setActiveView(view: AutoassessEditorComp.ActiveView?) {}
}