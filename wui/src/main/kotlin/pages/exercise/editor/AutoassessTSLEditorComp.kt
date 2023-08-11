package pages.exercise.editor

import pages.exercise.editor.tsl.TSLRootComp
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise

class AutoassessTSLEditorComp(
    private val evaluateScript: String,
    private val assets: Map<String, String>,
    startEditable: Boolean,
    private val onValidChanged: () -> Unit,
    parent: Component?,
) : AutoassessEditorComp(parent) {

    val tslRoot = TSLRootComp(this, assets, startEditable, onValidChanged)

    private var isEditable = startEditable

    override val children: List<Component>
        get() = listOf(tslRoot)

    override fun create() = doInPromise {

    }

    override suspend fun setEditable(nowEditable: Boolean) {
        isEditable = nowEditable
        tslRoot.setEditable(nowEditable)
    }

    override fun getEvalScript() = evaluateScript

    override fun getAssets() = tslRoot.getTslSpec()

    override fun isValid() = tslRoot.isValid()


    // TODO
    object ActiveView : AutoassessEditorComp.ActiveView

    override fun getActiveView() = ActiveView
    override fun setActiveView(view: AutoassessEditorComp.ActiveView?) {}
}