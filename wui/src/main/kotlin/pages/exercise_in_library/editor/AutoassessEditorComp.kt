package pages.exercise_in_library.editor

import components.code_editor.old.OldCodeEditorComp
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator

abstract class AutoassessEditorComp(
    parent: Component?,
    dstId: String = IdGenerator.nextId()
) : Component(parent, dstId) {

    companion object {
        const val EVAL_SCRIPT_FILENAME = "evaluate.sh"
        const val TSL_SPEC_FILENAME_YAML = "tsl.yaml"
        const val TSL_SPEC_FILENAME_JSON = "tsl.json"
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
        if (isEditable) OldCodeEditorComp.Edit.EDITABLE else OldCodeEditorComp.Edit.READONLY
}
