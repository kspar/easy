package components.code_editor

import libheaders.CodeMirror
import libheaders.CodeMirrorInstance
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.getElemBySelector
import rip.kspar.ezspa.objOf
import template

class CodeDiffEditorComp(
    private val left: File,
    private val right: File,
    private val softWrap: Boolean = false,
    private val showLineNumbers: Boolean = true,
    parent: Component?,
) : Component(parent) {

    data class File(val filename: String, val content: String)


    private lateinit var editor: CodeMirrorInstance

    override fun render() = template(
        """
            <ez-code-edit class='readonly'>           
                <!-- editor appended here -->
            </ez-code-edit>
        """.trimIndent(),
    )

    override fun postRender() {
        editor = CodeMirror.MergeView(
            getElemBySelector("#$dstId ez-code-edit"),
            objOf(
                "value" to left.content,
                "origRight" to right.content,
                "revertButtons" to false,
                "mode" to CodeMirror.findModeByFileName(left.filename)?.mode,
                "readOnly" to true,
                "lineNumbers" to showLineNumbers,
                "lineWrapping" to softWrap,
                "autoRefresh" to true,
                "viewportMargin" to 100,
                "theme" to "idea",
                "indentUnit" to 4,
                "matchBrackets" to true,
                "cursorScrollMargin" to 1,
            )
        )
    }
}