package pages.exercise_in_library.editor

import components.code_editor.CodeEditorComp
import kotlinx.coroutines.await
import rip.kspar.ezspa.plainDstStr
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise

class AutoassessCodeEditorComp(
    private val evaluateScript: String,
    private val assets: Map<String, String>,
    startEditable: Boolean,
    parent: Component?,
) : AutoassessEditorComp(parent) {

    private var isEditable = startEditable

    private lateinit var codeEditorComp: CodeEditorComp

    override val children: List<Component>
        get() = listOf(codeEditorComp)

    override fun create() = doInPromise {
        codeEditorComp = CodeEditorComp(
            listOf(CodeEditorComp.File(EVAL_SCRIPT_FILENAME, evaluateScript, editorEditable(isEditable))) +
                    assets.toList().sortedBy { it.first }.map {
                        CodeEditorComp.File(it.first, it.second, editorEditable(isEditable))
                    },
            fileCreator = if (isEditable) CodeEditorComp.CreateFile(CodeEditorComp.Edit.EDITABLE) else null,
            parent = this,
        )
    }

    override fun render() = plainDstStr(codeEditorComp.dstId)

    override suspend fun setEditable(nowEditable: Boolean) {
        isEditable = nowEditable
        createAndBuild().await()
    }

    override fun getEvalScript(): String {
        // TODO: codeEditorComp.getFileValue(EVAL_SCRIPT_FILENAME)?
        val files = codeEditorComp.getAllFiles().associate { it.name to it.content.orEmpty() }
        return files[EVAL_SCRIPT_FILENAME]!!
    }

    override fun getAssets(): Map<String, String> {
        val files = codeEditorComp.getAllFiles().associate { it.name to it.content.orEmpty() }
        return files - EVAL_SCRIPT_FILENAME
    }

    // Not checking anything in editor
    override fun isValid() = true

    data class ActiveView(
        val editorTabId: String?,
    ) : AutoassessEditorComp.ActiveView

    override fun getActiveView() = ActiveView(codeEditorComp.getActiveTabFilename())

    override fun setActiveView(view: AutoassessEditorComp.ActiveView?) {
        (view as? ActiveView)?.editorTabId?.let { codeEditorComp.setActiveTabByFilename(it) }
    }
}