package pages.exercise.editor

import components.code_editor.CodeEditorComp
import kotlinx.coroutines.await
import plainDstStr
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise

class AutoassessCodeEditorComp(
    private val evaluateScript: String,
    private val assets: Map<String, String>,
    startEditable: Boolean,
    parent: Component?,
) : AutoassessEditorComp(parent) {

    companion object {
        const val EVAL_SCRIPT_FILENAME = "evaluate.sh"
    }

    private var isEditable = startEditable

    private lateinit var codeEditorComp: CodeEditorComp

    override val children: List<Component>
        get() = listOf(codeEditorComp)

    override fun create() = doInPromise {
        codeEditorComp = CodeEditorComp(
            listOf(CodeEditorComp.File(EVAL_SCRIPT_FILENAME, evaluateScript, "shell", editorEditable(isEditable))) +
                    assets.toList().sortedBy { it.first }.map {
                        CodeEditorComp.File(it.first, it.second, "python", editorEditable(isEditable))
                    },
            fileCreator = if (isEditable) CodeEditorComp.CreateFile("python", CodeEditorComp.Edit.EDITABLE) else null,
            parent = this,
        )
    }

    override fun render() = plainDstStr(codeEditorComp.dstId)

    override suspend fun setEditable(nowEditable: Boolean) {
        isEditable = nowEditable
        createAndBuild().await()
    }

    override fun getEvalScript(): String {
        val files = codeEditorComp.getAllFiles().associate { it.name to it.content.orEmpty() }
        return files[EVAL_SCRIPT_FILENAME]!!
    }

    override fun getAssets(): Map<String, String> {
        val files = codeEditorComp.getAllFiles().associate { it.name to it.content.orEmpty() }
        return files - EVAL_SCRIPT_FILENAME
    }

    // Not checking anything in editor
    override fun isValid() = true

    private fun editorEditable(isEditable: Boolean) =
        if (isEditable) CodeEditorComp.Edit.EDITABLE else CodeEditorComp.Edit.READONLY

}