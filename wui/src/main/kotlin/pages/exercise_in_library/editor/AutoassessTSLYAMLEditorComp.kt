package pages.exercise_in_library.editor

import components.code_editor.CodeEditorComp
import dao.TSLDAO
import debug
import kotlinx.coroutines.await
import observeValueChange
import org.w3c.dom.Element
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.getElemById
import rip.kspar.ezspa.getElemByIdOrNull
import template

class AutoassessTSLYAMLEditorComp(
    private val evaluateScript: String,
    private val assets: Map<String, String>,
    startEditable: Boolean,
    private val onValidChanged: (Boolean) -> Unit,
    parent: Component?,
) : AutoassessEditorComp(parent) {

    private lateinit var codeEditor: CodeEditorComp

    private var isEditable = startEditable
    private val tslSpec = assets.getOrElse(TSL_SPEC_FILENAME_YAML) { "" }
    private val generatedAssets = assets - TSL_SPEC_FILENAME_YAML

    private var isUpToDate = true

    val compilerFeedbackEl: Element
        get() = getElemById("tsl-yaml-compiler-feedback")


    override val children: List<Component>
        get() = listOf(codeEditor)

    override fun create() = doInPromise {
        codeEditor = CodeEditorComp(
            listOf(
                CodeEditorComp.File(EVAL_SCRIPT_FILENAME, evaluateScript, isEditable = isEditable),
                CodeEditorComp.File(TSL_SPEC_FILENAME_YAML, tslSpec, isEditable = isEditable),
            ) + generatedAssets.toList().sortedBy { it.first }.map {
                CodeEditorComp.File(it.first, it.second, isEditable = false)
            },
            parent = this,
        )
    }

    override fun render() = template(
        """
            <pre id="tsl-yaml-compiler-feedback"></pre>
            <ez-dst id="${codeEditor.dstId}"></ez-dst>
        """.trimIndent(),
    )

    override fun postChildrenBuilt() {
        if (isEditable) {
            doInPromise {
                observeValueChange(1000, 300,
                    valueProvider = { codeEditor.getContent(TSL_SPEC_FILENAME_YAML) },
                    continuationConditionProvider = { getElemByIdOrNull(codeEditor.dstId) != null && isEditable },
                    action = ::compile,
                    idleCallback = {
                        isUpToDate = false
                        onValidChanged(isUpToDate)
                        debug { "Change in TSL spec detected, waiting for idle" }
                    }
                )
            }
        }
    }

    private suspend fun compile(tslSpec: String) {
        try {
            val result = TSLDAO.compile(tslSpec, TSLDAO.FORMAT.YAML).await()
            debug { "Compilation finished" }

            compilerFeedbackEl.textContent = result.feedback

            result.scripts?.forEach {
                codeEditor.setContent(it.value, it.name)
                codeEditor.setFileProps(filename = it.name, editable = false, renameable = false, deletable = false)
            }

            result.meta?.let {
                val metaFile = """
                                    Compiled at: ${it.timestamp.date.toUTCString()}
                                    Compiler version: ${it.compiler_version}
                                    Backend: ${it.backend_id} ${it.backend_version}
                               """.trimIndent()
                codeEditor.setContent(metaFile, TSL_META_FILENAME)
                codeEditor.setFileProps(
                    filename = TSL_META_FILENAME, editable = false, renameable = false, deletable = false
                )
            }
        } finally {
            isUpToDate = true
            onValidChanged(isUpToDate)
        }
    }

    override suspend fun setEditable(nowEditable: Boolean) {
        isEditable = nowEditable
        createAndBuild().await()
    }

    override fun getEvalScript(): String = codeEditor.getContent(EVAL_SCRIPT_FILENAME)

    override fun getAssets(): Map<String, String> {
        val files = codeEditor.getAllFiles()
        return files - EVAL_SCRIPT_FILENAME
    }

    override fun isValid() = isUpToDate


    data class ActiveView(
        val editorTabId: String?,
    ) : AutoassessEditorComp.ActiveView

    override fun getActiveView() = ActiveView(codeEditor.getActiveFilename())

    override fun setActiveView(view: AutoassessEditorComp.ActiveView?) {
        (view as? ActiveView)?.editorTabId?.let { codeEditor.setActiveFilename(it) }
    }
}