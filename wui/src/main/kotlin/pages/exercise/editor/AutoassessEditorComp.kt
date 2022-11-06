package pages.exercise.editor

import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator

abstract class AutoassessEditorComp(
    parent: Component?,
    dstId: String = IdGenerator.nextId()
) : Component(parent, dstId) {

    abstract suspend fun setEditable(nowEditable: Boolean)

    abstract fun getEvalScript(): String

    abstract fun getAssets(): Map<String, String>

    abstract fun isValid(): Boolean


    interface ActiveView

    abstract fun getActiveView(): ActiveView
    abstract fun setActiveView(view: ActiveView?)
}
