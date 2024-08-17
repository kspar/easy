package pages.exercise_in_library.editor

import components.code_editor.CodeEditorComp
import kotlinx.coroutines.await
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
            files = listOf(
                CodeEditorComp.File(EVAL_SCRIPT_FILENAME, evaluateScript, isEditable)
            ) + assets.toList().sortedBy { it.first }.map {
                CodeEditorComp.File(
                    it.first, it.second,
                    isEditable = isEditable,
                    isRenameable = isEditable,
                    isDeletable = isEditable
                )
            },
            canCreateNewFiles = isEditable,
            parent = this,
        )
    }

    override suspend fun setEditable(nowEditable: Boolean) {
        isEditable = nowEditable
        createAndBuild().await()
    }

    override fun getEvalScript() = codeEditorComp.getContent(EVAL_SCRIPT_FILENAME)

    override fun getAssets(): Map<String, String> = codeEditorComp.getAllFiles() - EVAL_SCRIPT_FILENAME

    // Not checking anything in editor
    override fun isValid() = true

    data class ActiveView(
        val activeFileName: String,
    ) : AutoassessEditorComp.ActiveView

    override fun getActiveView() = ActiveView(codeEditorComp.getActiveFilename())

    override fun setActiveView(view: AutoassessEditorComp.ActiveView?) {
        (view as? ActiveView)?.activeFileName?.let { codeEditorComp.setActiveFilename(it) }
    }
}