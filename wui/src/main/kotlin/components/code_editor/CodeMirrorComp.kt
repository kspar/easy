package components.code_editor

import libheaders.CodeMirror
import libheaders.CodeMirrorInstance
import libheaders.tabHandler
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.getElemById
import rip.kspar.ezspa.objOf
import template

class CodeMirrorComp(
    initialFiles: List<CodeEditorComp.File>,
    private val initialActiveFilename: String? = null,
    private val placeholder: String = "",
    private val lineNumbers: Boolean = true,
    private val softWrap: Boolean = false,
    parent: Component?,
) : Component(parent) {

    private data class Tab(
        val filename: String,
        val doc: CodeMirror.Doc,
        val lang: dynamic
    )

    private lateinit var editor: CodeMirrorInstance

    private val textareaId = IdGenerator.nextId()

    private val files = initialFiles.map {
        val mode = it.lang ?: CodeMirror.findModeByFileName(it.name)?.mode
        Tab(it.name, CodeMirror.Doc(it.content.orEmpty(), mode), mode)
    }


    override fun render() = template(
        """
            <textarea id="{{textareaId}}"></textarea>
        """.trimIndent(),
        "textareaId" to textareaId,
    )

    override fun postRender() {
        editor = CodeMirror.fromTextArea(
            getElemById(textareaId),
            objOf(
                "lineNumbers" to lineNumbers,
                "lineWrapping" to softWrap,
                "placeholder" to placeholder,
                "autoRefresh" to true,
                "viewportMargin" to 100,
                "theme" to "idea",
                "indentUnit" to 4,
                "matchBrackets" to true,
                "extraKeys" to tabHandler,
                "undoDepth" to 500,
                "cursorScrollMargin" to 1,
            )
        )


        files.forEach { CodeMirror.autoLoadMode(editor, it.lang) }

        val activeFile = files.firstOrNull { it.filename == initialActiveFilename } ?: files.first()
        editor.swapDoc(activeFile.doc)
    }

    fun getContent(filename: String): String {
        val file = files.first { it.filename == filename }
        return file.doc.getValue()
    }

    fun switchToFile(filename: String) {
        val file = files.first { it.filename == filename }
        editor.swapDoc(file.doc)
    }

    fun setEditable(isEditable: Boolean) {
        editor.setOption("readOnly", !isEditable)
    }
}