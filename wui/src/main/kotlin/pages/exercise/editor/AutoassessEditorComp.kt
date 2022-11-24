package pages.exercise.editor

import components.code_editor.CodeEditorComp
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator

abstract class AutoassessEditorComp(
    parent: Component?,
    dstId: String = IdGenerator.nextId()
) : Component(parent, dstId) {

    companion object {
        const val EVAL_SCRIPT_FILENAME = "evaluate.sh"
        const val TSL_SPEC_FILENAME = "tsl.yaml"
        const val TSL_META_FILENAME = "meta.txt"
    }

    abstract suspend fun setEditable(nowEditable: Boolean)

    abstract fun getEvalScript(): String

    abstract fun getAssets(): Map<String, String>

    abstract fun isValid(): Boolean


    interface ActiveView

    abstract fun getActiveView(): ActiveView
    abstract fun setActiveView(view: ActiveView?)


    protected fun editorEditable(isEditable: Boolean) =
        if (isEditable) CodeEditorComp.Edit.EDITABLE else CodeEditorComp.Edit.READONLY
}
