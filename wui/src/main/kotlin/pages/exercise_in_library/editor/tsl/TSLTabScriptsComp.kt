package pages.exercise_in_library.editor.tsl

import components.code_editor.old.OldCodeEditorComp
import pages.exercise_in_library.editor.AutoassessEditorComp.Companion.TSL_META_FILENAME
import rip.kspar.ezspa.Component


class TSLTabScriptsComp(
    private val initialScripts: Map<String, String>,
    parent: Component
) : Component(parent) {

    private val editor = OldCodeEditorComp(
        initialScripts.map { OldCodeEditorComp.File(it.key, it.value, OldCodeEditorComp.Edit.READONLY) },
        showTabs = true,
        parent = this,
    )

    override val children: List<Component>
        get() = listOf(editor)


    fun getScripts() = editor.getAllFiles().associate { it.name to it.content.orEmpty() }

    fun setScripts(scripts: Map<String, String>) {
        // TODO: remove all files first
        scripts.forEach {
            editor.setFileValue(it.key, it.value, OldCodeEditorComp.Edit.READONLY)
        }
    }

    override fun hasUnsavedChanges() = (initialScripts - TSL_META_FILENAME) != (getScripts() - TSL_META_FILENAME)

    fun refreshEditor() = editor.refresh()
}

