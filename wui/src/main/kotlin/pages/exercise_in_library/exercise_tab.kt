package pages.exercise_in_library

import EzDate
import MathJax
import components.UnorderedListComp
import components.code_editor.CodeEditorComp
import components.form.StringFieldComp
import components.form.validation.StringConstraints
import dao.ExerciseDAO
import highlightCode
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import lightboxExerciseImages
import observeValueChange
import pages.course_exercise.ExerciseSummaryPage
import queries.ReqMethod
import queries.fetchEms
import queries.http200
import queries.parseTo
import rip.kspar.ezspa.*
import tmRender
import warn
import kotlin.js.Promise


class ExerciseTabComp(
    private val exercise: ExerciseDAO.Exercise,
    private val onValidChanged: (Boolean) -> Unit,
    parent: Component?
) : Component(parent) {

    data class ExerciseProps(
        val title: String,
        val textAdoc: String,
        val textHtml: String,
        val embedConfig: ExerciseDAO.EmbedConfig?,
    )

    private lateinit var attributes: ExerciseAttributesComp
    private lateinit var textView: ExerciseTextComp

    override val children: List<Component>
        get() = listOf(attributes, textView)

    override fun create(): Promise<*> = doInPromise {
        attributes = ExerciseAttributesComp(exercise, onValidChanged, this)
        textView = ExerciseTextComp(exercise.text_adoc, exercise.text_html, this)
    }

    override fun render(): String = plainDstStr(attributes.dstId, textView.dstId)

    suspend fun setEditable(nowEditable: Boolean) {
        attributes.setEditable(nowEditable)
        textView.setEditable(nowEditable)
    }

    fun isValid() = attributes.isValid()

    fun getEditedProps() = ExerciseProps(
        attributes.getEditedTitle().also { it ?: warn { "editedTitle == null" } }.orEmpty(),
        textView.getEditedAdoc().also { it ?: warn { "editedAdoc == null" } }.orEmpty(),
        textView.getEditedHtml().also { it ?: warn { "editedHtml == null" } }.orEmpty(),
        attributes.getEmbedConfig(),
    )
}


class ExerciseAttributesComp(
    private val exercise: ExerciseDAO.Exercise,
    private val onValidChange: (Boolean) -> Unit,
    parent: Component?
) : Component(parent) {

    private lateinit var onCoursesList: UnorderedListComp
    private lateinit var titleComp: Component

    override val children: List<Component>
        get() = listOf(onCoursesList, titleComp)

    override fun create() = doInPromise {
        onCoursesList = UnorderedListComp(
            exercise.on_courses.sortedByDescending { it.id.toInt() }.map {
                UnorderedListComp.Item(
                    it.effectiveTitle + (it.course_exercise_title_alias?.let { " ($it)" } ?: ""),
                    ExerciseSummaryPage.link(it.id, it.course_exercise_id)
                )
            } + if (exercise.on_courses_no_access > 0)
                listOf(UnorderedListComp.Item("(+ ${exercise.on_courses_no_access} peidetud kursust)"))
            else emptyList(),
            maxItemsToShow = 5,
            parent = this
        )
        titleComp = ExerciseTitleViewComp(exercise.title, this)
    }

    override fun render(): String = tmRender(
        "t-c-exercise-tab-exercise-attrs",
        "createdAtLabel" to "Loodud",
        "modifiedAtLabel" to "Viimati muudetud",
        "onCoursesLabel" to "Kasutusel kursustel",
        "notUsedOnAnyCoursesLabel" to "Mitte ühelgi!",
        "createdAt" to exercise.created_at.toHumanString(EzDate.Format.DATE),
        "createdBy" to exercise.owner_id,
        "modifiedAt" to exercise.last_modified.toHumanString(EzDate.Format.DATE),
        "modifiedBy" to exercise.last_modified_by_id,
        "onCoursesListDst" to onCoursesList.dstId,
        "onCoursesCount" to (exercise.on_courses.size + exercise.on_courses_no_access),
        "titleDstId" to titleComp.dstId,
    )

    override fun postChildrenBuilt() {
        (titleComp as? StringFieldComp)?.validateInitial()
    }

    suspend fun setEditable(nowEditable: Boolean) {
        if (nowEditable) {
            titleComp = StringFieldComp(
                "Ülesande pealkiri", true,
                initialValue = exercise.title,
                constraints = listOf(StringConstraints.Length(max = 100)),
                onValidChange = onValidChange,
                parent = this
            )
            rebuild()
        } else {
            createAndBuild().await()
        }
    }

    fun getEditedTitle() = (titleComp as? StringFieldComp)?.getValue()

    fun getEmbedConfig() = if (exercise.is_anonymous_autoassess_enabled)
        ExerciseDAO.EmbedConfig(exercise.anonymous_autoassess_template)
    else null

    fun isValid() = titleComp.let { if (it is StringFieldComp) it.isValid else true }
}

class ExerciseTitleViewComp(
    private val title: String,
    parent: Component?
) : Component(parent) {

    override fun render() = tmRender(
        "t-c-exercise-tab-title-view",
        "title" to title
    )
}


class ExerciseTextComp(
    private val textAdoc: String?,
    private val textHtml: String?,
    parent: Component?
) : Component(parent) {

    private var editEnabled = false

    private lateinit var modeComp: Component

    override val children: List<Component>
        get() = listOf(modeComp)

    override fun create(): Promise<*> = doInPromise {
        modeComp = ExerciseTextViewComp(textHtml, this)
    }

    override fun render(): String = plainDstStr(modeComp.dstId)

    suspend fun setEditable(nowEditable: Boolean) {
        editEnabled = nowEditable
        if (nowEditable) {
            modeComp = ExerciseTextEditComp(textAdoc, textHtml, this)
            rebuildAndRecreateChildren().await()
        } else {
            createAndBuild().await()
        }
    }

    fun getEditedAdoc() = (modeComp as? ExerciseTextEditComp)?.getCurrentAdoc()
    fun getEditedHtml() = (modeComp as? ExerciseTextEditComp)?.getCurrentHtml()
}


class ExerciseTextViewComp(
    private val textHtml: String?,
    parent: Component?
) : Component(parent) {

    override fun render(): String = tmRender(
        "t-c-exercise-tab-exercise-text-view",
        "html" to textHtml
    )

    override fun postRender() {
        highlightCode()
        MathJax.formatPageIfNeeded(textHtml.orEmpty())
        lightboxExerciseImages()
    }
}


class ExerciseTextEditComp(
    private val textAdoc: String?,
    private val textHtml: String?,
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

    private lateinit var editor: CodeEditorComp
    private lateinit var preview: ExercisePreviewComp

    override val children: List<Component>
        get() = listOf(editor, preview)

    override fun create(): Promise<*> = doInPromise {
        editor = CodeEditorComp(
            listOf(
                CodeEditorComp.File(ADOC_FILENAME, textAdoc, lang = "asciidoc"),
                CodeEditorComp.File(
                    HTML_FILENAME,
                    textHtml,
                    CodeEditorComp.Edit.READONLY,
                    objOf("name" to "xml", "htmlMode" to true),
                )
            ), softWrap = true, parent = this
        )
        preview = ExercisePreviewComp(this)
    }

    override fun postRender() {
        doInPromise {
            observeValueChange(500, 250,
                // if adoc exists, then show preview at start as well, else keep legacy html in editor
                doActionFirst = textAdoc != null,
                valueProvider = { getCurrentAdoc() },
                continuationConditionProvider = { getElemByIdOrNull(editor.dstId) != null },
                action = {
                    preview.stateToUpdating()
                    val html = fetchAdocPreview(it)
                    preview.stateToUpdated(html)
                    editor.setFileValue(HTML_FILENAME, html)
                },
                idleCallback = {
                    preview.stateToWaiting()
                }
            )
        }
    }

    override fun render(): String = tmRender(
        "t-c-exercise-tab-exercise-text-edit",
        "editorDstId" to editor.dstId,
        "previewDstId" to preview.dstId,
    )

    fun getCurrentAdoc() = editor.getFileValue(ADOC_FILENAME)
    fun getCurrentHtml() = editor.getFileValue(HTML_FILENAME)

    private suspend fun fetchAdocPreview(adoc: String): String {
        return fetchEms("/preview/adoc", ReqMethod.POST, mapOf("content" to adoc),
            successChecker = { http200 }).await()
            .parseTo(HtmlPreview.serializer()).await().content
    }
}


class ExercisePreviewComp(
    parent: Component?
) : Component(parent) {

    enum class Status {
        WAITING, UPDATING, UPDATED
    }

    private var status: Status = Status.WAITING

    override fun render(): String = tmRender(
        "t-c-exercise-tab-exercise-text-edit-preview",
        "previewLabel" to "Eelvaade"
    )

    fun stateToWaiting() {
        status = Status.WAITING
        updateStatusIndicator()
    }

    fun stateToUpdating() {
        status = Status.UPDATING
        updateStatusIndicator()
    }

    fun stateToUpdated(html: String) {
        status = Status.UPDATED
        getElemById("exercise-text").innerHTML = html
        highlightCode()
        MathJax.formatPageIfNeeded(html)
        lightboxExerciseImages()
        updateStatusIndicator()
    }

    private fun updateStatusIndicator() {
        // TODO: maybe someday
    }
}
