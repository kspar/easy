package pages.exercise_in_library

import components.code_editor.AdocEditorComp
import components.form.StringFieldComp
import components.form.validation.StringConstraints
import dao.ExerciseDAO
import kotlinx.coroutines.await
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import template
import translation.Str
import kotlin.js.Promise

class ExerciseTextEditTabComp(
    private val title: String,
    private val textAdoc: String,
    private val exerciseTextChanged: suspend (newHtml: String) -> Unit,
    private val exerciseTitleChanged: suspend (newTitle: String) -> Unit,
    private val validChanged: (Boolean) -> Unit,
) : Component() {

    private lateinit var titleField: StringFieldComp
    private lateinit var editor: AdocEditorComp

    override val children: List<Component>
        get() = listOf(titleField, editor)

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

        editor = AdocEditorComp(
            textAdoc,
            placeholder = Str.exerciseTextEditorPlaceholder,
            inlinePreview = false,
            isEditable = false,
            onContentChanged = {
                val html = ExerciseDAO.previewAdocContent(it).await()
                exerciseTextChanged(html)
            },
            parent = this
        )
    }

    override fun render() = template(
        """
            <ez-exercise-edit-tab>
                $titleField
                $editor
            </ez-exercise-edit-tab>
        """.trimIndent(),
    )

    override fun postChildrenBuilt() {
        titleField.validateInitial()
    }


    fun getCurrentTitle() = titleField.getValue()
    fun getCurrentAdoc() = editor.getContent()

    suspend fun setEditable(nowEditable: Boolean) {
        titleField.isDisabled = !nowEditable
        titleField.rebuild()
        editor.setEditable(nowEditable)
        if (!nowEditable) {
            editor.setContent(textAdoc)
        }
    }

    fun isValid() = titleField.isValid

    override fun hasUnsavedChanges() = getCurrentTitle() != title || getCurrentAdoc() != textAdoc
}