package pages.exercise_in_library.editor.tsl

import components.code_editor.CodeEditorComp
import pages.exercise_in_library.editor.AutoassessEditorComp.Companion.TSL_META_FILENAME
import rip.kspar.ezspa.Component


class TSLTabScriptsComp(
    private val initialScripts: Map<String, String>,
    parent: Component
) : Component(parent) {

    private val editor = CodeEditorComp(
        initialScripts.map { CodeEditorComp.File(it.key, it.value, isEditable = false) },
        parent = this,
    )

    override val children: List<Component>
        get() = listOf(editor)

    suspend fun setScripts(scripts: Map<String, String>) {
        // TODO: should remove all files first, but don't want to risk it at the moment since our editor doesn't support 0 files
        scripts.forEach {
            editor.setContent(filename = it.key, content = it.value)
            editor.setFileProps(filename = it.key, editable = false, renameable = false, deletable = false)
        }
    }

    override fun hasUnsavedChanges() = (initialScripts - TSL_META_FILENAME) != (getScripts() - TSL_META_FILENAME)

    private fun getScripts() = editor.getAllFiles()
}
