package pages.exercise_in_library

import components.code_editor.CodeEditorComp
import components.form.StringFieldComp
import components.form.validation.StringConstraints
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import observeValueChange
import queries.ReqMethod
import queries.fetchEms
import queries.http200
import queries.parseTo
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.getElemByIdOrNull
import rip.kspar.ezspa.objOf
import template
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

    @Serializable
    data class HtmlPreview(
        val content: String
    )

    private lateinit var titleField: StringFieldComp
    private lateinit var editor: CodeEditorComp

    override val children: List<Component>
        get() = listOf(titleField, editor)

    override fun create(): Promise<*> = doInPromise {
        titleField = StringFieldComp(
            "Ülesande pealkiri", true,
            initialValue = title,
            isDisabled = true,
            onValueChange = exerciseTitleChanged,
            constraints = listOf(StringConstraints.Length(max = 100)),
            onValidChange = validChanged,
            parent = this
        )

        editor = CodeEditorComp(
            listOf(
                CodeEditorComp.File(ADOC_FILENAME, textAdoc, CodeEditorComp.Edit.READONLY, lang = "asciidoc"),
                CodeEditorComp.File(
                    HTML_FILENAME,
                    textHtml,
                    CodeEditorComp.Edit.READONLY,
                    objOf("name" to "xml", "htmlMode" to true),
                )
            ), softWrap = true, parent = this
        )
    }

    override fun render() = template(
        """
            <ez-exercise-edit-tab>
                <ez-dst id='${titleField.dstId}'></ez-dst>
                <ez-dst id='${editor.dstId}'></ez-dst>            
            </ez-exercise-edit-tab>
        """.trimIndent(),
    )

    override fun postRender() {
        doInPromise {
            observeValueChange(300, 150,
                // if adoc exists, then show preview at start as well, else keep legacy html in editor
                doActionFirst = textAdoc.isNotEmpty(),
                valueProvider = { getCurrentAdoc() },
                continuationConditionProvider = { getElemByIdOrNull(editor.dstId) != null },
                action = {
                    val html = fetchAdocPreview(it)
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

    private suspend fun fetchAdocPreview(adoc: String): String {
        return fetchEms("/preview/adoc", ReqMethod.POST, mapOf("content" to adoc),
            successChecker = { http200 }).await()
            .parseTo(HtmlPreview.serializer()).await().content
    }
}