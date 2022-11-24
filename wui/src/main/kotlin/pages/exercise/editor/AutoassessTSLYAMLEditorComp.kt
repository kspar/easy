package pages.exercise.editor

import components.StringComp
import components.code_editor.CodeEditorComp
import dao.TSLDAO
import debug
import kotlinx.coroutines.await
import observeValueChange
import plainDstStr
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.getElemByIdOrNull

class AutoassessTSLYAMLEditorComp(
    private val evaluateScript: String,
    private val assets: Map<String, String>,
    startEditable: Boolean,
    parent: Component?,
) : AutoassessEditorComp(parent) {

    private val compilerFeedback = StringComp("", this)
    private lateinit var codeEditor: CodeEditorComp

    private var isEditable = startEditable
    private val tslSpec = assets.getOrElse(TSL_SPEC_FILENAME) { "" }
    private val generatedAssets = assets - TSL_SPEC_FILENAME


    override val children: List<Component>
        get() = listOf(compilerFeedback, codeEditor)

    override fun create() = doInPromise {
        codeEditor = CodeEditorComp(
            listOf(
                CodeEditorComp.File(EVAL_SCRIPT_FILENAME, evaluateScript, "shell", editorEditable(isEditable)),
                CodeEditorComp.File(TSL_SPEC_FILENAME, tslSpec, "yaml", editorEditable(isEditable)),
            ) + generatedAssets.toList().sortedBy { it.first }.map {
                // TODO: detect mode, e.g. for meta.txt
                CodeEditorComp.File(it.first, it.second, "python", CodeEditorComp.Edit.READONLY)
            },
            parent = this,
        )
    }

    override fun render() = plainDstStr(compilerFeedback.dstId, codeEditor.dstId)

    override fun postRender() {
        if (isEditable) {
            doInPromise {
                observeValueChange(2000, 500,
                    valueProvider = { codeEditor.getFileValue(TSL_SPEC_FILENAME) },
                    continuationConditionProvider = { getElemByIdOrNull(codeEditor.dstId) != null && isEditable },
                    action = {
                        val result = TSLDAO.compile(it).await()
                        debug { "Compilation finished" }

                        result.feedback?.let {
                            compilerFeedback.parts = StringComp.simpleText(it)
                        }

                        result.scripts?.forEach {
                            codeEditor.setFileValue(it.name, it.value, newFileEdit = CodeEditorComp.Edit.READONLY)
                        }

                        result.meta?.let {
                            val metaFile = """
                                ${it.timestamp.date.toUTCString()}
                                Compiler version: ${it.compiler_version}
                                Backend: ${it.backend_id} ${it.backend_version}
                                """.trimIndent()
                            codeEditor.setFileValue(
                                TSL_META_FILENAME, metaFile, "null", CodeEditorComp.Edit.READONLY
                            )
                        }
                    },
                    idleCallback = {
                        debug { "Change in TSL spec detected, waiting for idle" }
                    }
                )
            }
        }
    }

    override suspend fun setEditable(nowEditable: Boolean) {
        isEditable = nowEditable
        createAndBuild().await()
    }

    override fun getEvalScript(): String {
        return codeEditor.getFileValue(EVAL_SCRIPT_FILENAME)
    }

    override fun getAssets(): Map<String, String> {
        val files = codeEditor.getAllFiles().associate { it.name to it.content.orEmpty() }
        return files - EVAL_SCRIPT_FILENAME
    }

    override fun isValid() = true


    data class ActiveView(
        val editorTabId: String?,
    ) : AutoassessEditorComp.ActiveView

    override fun getActiveView() = ActiveView(codeEditor.getActiveTabFilename())

    override fun setActiveView(view: AutoassessEditorComp.ActiveView?) {
        (view as? ActiveView)?.editorTabId?.let { codeEditor.setActiveTabByFilename(it) }
    }
}