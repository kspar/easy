package components.code_editor

import Icons
import components.IconButtonComp
import components.code_editor.parts.HtmlViewEditorTrailerComp
import dao.ExerciseDAO
import kotlinx.browser.window
import kotlinx.coroutines.await
import observeValueChange
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.getElemByIdOrNull
import template

class AdocEditorComp(
    private val adoc: String,
    private val placeholder: String = "",
    private val inlinePreview: Boolean = true,
    private val isEditable: Boolean = true,
    private val onContentChanged: suspend (newContent: String) -> Unit,
    parent: Component?,
) : Component(parent) {

    private lateinit var editor: CodeEditorComp
    private var preview: HtmlViewEditorTrailerComp? = null
    private lateinit var helpLink: IconButtonComp

    override val children
        get() = listOf(editor, helpLink)

    override fun create() = doInPromise {

        preview = if (inlinePreview)
            HtmlViewEditorTrailerComp(parent = this)
        else null

        editor = CodeEditorComp(
            listOf(CodeEditorComp.File("", adoc, isEditable = isEditable, lang = "asciidoc")),
            placeholder = placeholder,
            headerVisible = false,
            tabs = false,
            lineNumbers = false,
            softWrap = true,
            footerComp = preview,
            parent = this
        )

        // Temporary, help link will be in the toolbar when we have the styles toolbar
        helpLink = IconButtonComp(
            Icons.helpUnf, "AsciiDoc Quick Reference",
            onClick = { window.open("https://docs.asciidoctor.org/asciidoc/latest/syntax-quick-reference/", "_blank") },
            parent = this
        )
    }

    override fun render() = template(
        """
            <div style='position: relative;'>
                $editor
                <span style='position: absolute; top: 5px; right: 5px;'>
                    $helpLink
                </span>
            </div>    
        """.trimIndent(),
    )

    override fun postChildrenBuilt() {
        doInPromise {
            observeValueChange(
                200, 200, doActionFirst = true,
                valueProvider = { getContent() },
                continuationConditionProvider = { getElemByIdOrNull(editor.dstId) != null },
                action = { newValue ->
                    preview?.set(ExerciseDAO.previewAdocContent(newValue).await())
                    onContentChanged(newValue)
                },
            )
        }
    }

    fun getContent() = editor.getContent().trim()

    suspend fun setContent(content: String) = editor.setContent(content)

    suspend fun setEditable(nowEditable: Boolean) = editor.setFileProps(editable = nowEditable)
}