package pages.exercise_in_library.editor

import pages.exercise_in_library.editor.tsl.TSLRootComp
import rip.kspar.ezspa.Component

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

    override suspend fun setEditable(nowEditable: Boolean) {
        isEditable = nowEditable
        tslRoot.setEditable(nowEditable)
    }

    override fun getEvalScript() = evaluateScript

    override fun getAssets() = tslRoot.getTslSpec()

    override fun isValid() = tslRoot.isValid()


    data class ActiveView(
        val tab: TSLRootComp.Tab,
        val openTests: List<Long>,
    ) : AutoassessEditorComp.ActiveView

    override fun getActiveView() = ActiveView(
        tslRoot.getActiveTab(),
        tslRoot.getOpenTests(),
    )

    override fun setActiveView(view: AutoassessEditorComp.ActiveView?) {
        (view as? ActiveView)?.let {
            tslRoot.setActiveTab(it.tab)
            tslRoot.openTests(it.openTests)
        }
    }
}