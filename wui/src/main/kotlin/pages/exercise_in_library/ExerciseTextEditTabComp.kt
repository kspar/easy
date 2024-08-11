package pages.exercise_in_library

import Icons
import components.code_editor.old.OldCodeEditorComp
import components.form.OldIconButtonComp
import components.form.StringFieldComp
import components.form.validation.StringConstraints
import dao.ExerciseDAO
import kotlinx.browser.window
import kotlinx.coroutines.await
import observeValueChange
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.getElemByIdOrNull
import rip.kspar.ezspa.objOf
import template
import translation.Str
import kotlin.js.Promise

class ExerciseTextEditTabComp(
    private val title: String,
    private val textAdoc: String,
    private val textHtml: String,
    private val exerciseTextChanged: suspend (newHtml: String) -> Unit,
    private val exerciseTitleChanged: suspend (newTitle: String) -> Unit,
    private val validChanged: (Boolean) -> Unit,
    parent: Component?
) : Component(parent) {

    companion object {
        private const val ADOC_FILENAME = "adoc"
        private const val HTML_FILENAME = "html"
    }

    private lateinit var titleField: StringFieldComp
    private val helpIcon = OldIconButtonComp(
        Icons.helpUnf,
        "AsciiDoc Quick Reference",
        onClick = { window.open("https://docs.asciidoctor.org/asciidoc/latest/syntax-quick-reference/", "_blank") },
        parent = this
    )
    private lateinit var editor: OldCodeEditorComp

    override val children: List<Component>
        get() = listOf(titleField, helpIcon, editor)

    override fun create(): Promise<*> = doInPromise {
        titleField = StringFieldComp(
            Str.exerciseTitle, true,
            initialValue = title,
            isDisabled = true,
            onValueChange = exerciseTitleChanged,
            constraints = listOf(StringConstraints.Length(max = 100)),
            onValidChange = validChanged,
            parent = this
        )

        editor = OldCodeEditorComp(
            listOf(
                OldCodeEditorComp.File(ADOC_FILENAME, textAdoc, OldCodeEditorComp.Edit.READONLY, lang = "asciidoc"),
                OldCodeEditorComp.File(
                    HTML_FILENAME,
                    textHtml,
                    OldCodeEditorComp.Edit.READONLY,
                    objOf("name" to "xml", "htmlMode" to true),
                )
            ), softWrap = true, parent = this
        )
    }

    override fun render() = template(
        """
            <ez-exercise-edit-tab>
                $titleField
                <div style='position: relative'>
                    <div style="position: absolute; right: 1rem; top: .2rem;" class='icon-med'>
                        $helpIcon
                    </div>
                    $editor
                </div>
            </ez-exercise-edit-tab>
        """.trimIndent(),
    )

    override fun postRender() {
        doInPromise {
            observeValueChange(
                300, 150,
                // if adoc exists, then show preview at start as well, else keep legacy html in editor
                doActionFirst = textAdoc.isNotEmpty(),
                valueProvider = { getCurrentAdoc() },
                continuationConditionProvider = { getElemByIdOrNull(editor.dstId) != null },
                action = {
                    val html = ExerciseDAO.previewAdocContent(it).await()
                    exerciseTextChanged(html)
                    editor.setFileValue(HTML_FILENAME, html)
                },
            )
        }
    }

    override fun postChildrenBuilt() {
        titleField.validateInitial()
    }


    fun getCurrentTitle() = titleField.getValue()
    fun getCurrentAdoc() = editor.getFileValue(ADOC_FILENAME)
    fun getCurrentHtml() = editor.getFileValue(HTML_FILENAME)

    fun setEditable(nowEditable: Boolean) {
        titleField.isDisabled = !nowEditable
        titleField.rebuild()
        editor.setFileEditable(ADOC_FILENAME, nowEditable)
        if (!nowEditable) {
            editor.setFileValue(ADOC_FILENAME, textAdoc)
            editor.setFileValue(HTML_FILENAME, textHtml)
        }
    }

    fun isValid() = titleField.isValid

    override fun hasUnsavedChanges() = getCurrentTitle() != title || getCurrentAdoc() != textAdoc
}