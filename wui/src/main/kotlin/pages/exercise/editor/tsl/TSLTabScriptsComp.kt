package pages.exercise.editor.tsl

import components.code_editor.CodeEditorComp
import dao.TSLDAO
import rip.kspar.ezspa.Component


class TSLTabScriptsComp(
    parent: Component
) : Component(parent) {

    private val editor = CodeEditorComp(
        CodeEditorComp.File("TODO", ""),
        showTabs = true,
        parent = this,
    )

    override val children: List<Component>
        get() = listOf(editor)


    fun setScripts(scripts: List<TSLDAO.CompiledScript>) {
        // TODO: remove all files first
        scripts.forEach {
            editor.setFileValue(it.name, it.value, CodeEditorComp.Edit.READONLY)
        }
    }
}

