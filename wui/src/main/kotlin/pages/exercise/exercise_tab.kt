package pages.exercise

import MathJax
import Str
import components.CodeEditorComp
import debug
import doInPromise
import getElemById
import getElemByIdAs
import getElemByIdOrNull
import highlightCode
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import lightboxExerciseImages
import objOf
import observeValueChange
import onSingleClickWithDisabled
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLButtonElement
import plainDstStr
import queries.ReqMethod
import queries.fetchEms
import queries.http200
import queries.parseTo
import Component
import tmRender
import toEstonianString
import kotlin.js.Promise


class ExerciseTabComp(
        private val exercise: ExerciseDTO,
        private val onSaveUpdatedExercise: suspend (exercise: ExerciseDTO) -> Unit,
        parent: Component?
) : Component(parent) {

    private lateinit var attributes: ExerciseAttributesComp
    private lateinit var textView: ExerciseTextComp

    override val children: List<Component>
        get() = listOf(attributes, textView)

    override fun create(): Promise<*> = doInPromise {
        attributes = ExerciseAttributesComp(exercise, onSaveUpdatedExercise, this)
        textView = ExerciseTextComp(exercise.text_adoc, exercise.text_html, ::handleAdocUpdate, this)
    }

    override fun render(): String = plainDstStr(attributes.dstId, textView.dstId)

    private suspend fun handleAdocUpdate(textAdoc: String) {
        exercise.text_adoc = textAdoc
        onSaveUpdatedExercise(exercise)
    }
}


class ExerciseAttributesComp(
        private val exercise: ExerciseDTO,
        private val onSaveUpdatedExercise: suspend (exercise: ExerciseDTO) -> Unit,
        parent: Component?
) : Component(parent) {

    override fun render(): String = tmRender("t-c-exercise-tab-exercise-attrs",
            "createdAtLabel" to "Loodud",
            "modifiedAtLabel" to "Viimati muudetud",
            "isPublicLabel" to "Avalik",
            "graderTypeLabel" to "Hindamine",
            "onCoursesLabel" to "Kasutusel kursustel",
            "notUsedOnAnyCoursesLabel" to "Mitte ühelgi!",
            "aliasLabel" to "alias",
            "createdAt" to exercise.created_at.toEstonianString(),
            "createdBy" to exercise.owner_id,
            "modifiedAt" to exercise.last_modified.toEstonianString(),
            "modifiedBy" to exercise.last_modified_by_id,
            "isPublic" to Str.translateBoolean(exercise.is_public),
            "graderType" to if (exercise.grader_type == GraderType.AUTO) Str.graderTypeAuto() else Str.graderTypeTeacher(),
            "onCourses" to exercise.on_courses.map {
                mapOf("name" to it.title, "alias" to it.course_exercise_title_alias, "courseId" to it.id, "courseExerciseId" to it.course_exercise_id)
            },
            "onCoursesCount" to exercise.on_courses.size,
            "title" to exercise.title
    )
}


class ExerciseTextComp(
        private val textAdoc: String?,
        private val textHtml: String?,
        private val onSaveUpdatedAdoc: suspend (textAdoc: String) -> Unit,
        parent: Component?
) : Component(parent) {

    private var editEnabled = false

    private lateinit var modeComp: Component

    override val children: List<Component>
        get() = listOf(modeComp)

    override fun create(): Promise<*> = doInPromise {
        modeComp = ExerciseTextViewComp(textHtml, ::enableEditMode, this)
    }

    override fun render(): String = plainDstStr(modeComp.dstId)

    private suspend fun enableEditMode() {
        debug { "Enable edit mode" }
        editEnabled = true
        modeComp = ExerciseTextEditComp(textAdoc, onSaveUpdatedAdoc, ::disableEditMode, this)
        rebuild().await()
        onStateChanged()
    }

    private suspend fun disableEditMode() {
        debug { "Disable edit mode" }
        editEnabled = false
        createAndBuild().await()
        onStateChanged()
    }
}


class ExerciseTextViewComp(
        private val textHtml: String?,
        private val onEnableEditMode: suspend () -> Unit,
        parent: Component?
) : Component(parent) {

    override fun render(): String = tmRender("t-c-exercise-tab-exercise-text-view",
            "doEditLabel" to "Muuda teksti",
            "html" to textHtml
    )

    override fun postRender() {
        getElemByIdAs<HTMLAnchorElement>("exercise-text-enable-edit").onSingleClickWithDisabled(null) {
            onEnableEditMode()
        }
        highlightCode()
        MathJax.formatPageIfNeeded(textHtml.orEmpty())
        lightboxExerciseImages()
    }
}


class ExerciseTextEditComp(
        private val textAdoc: String?,
        private val onSaveUpdatedAdoc: suspend (textAdoc: String) -> Unit,
        private val onCancelEdit: suspend () -> Unit,
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
        editor = CodeEditorComp(listOf(
                CodeEditorComp.File(ADOC_FILENAME, textAdoc, "asciidoc"),
                CodeEditorComp.File(HTML_FILENAME, null, objOf("name" to "xml", "htmlMode" to true), CodeEditorComp.Edit.READONLY)
        ), this)
        preview = ExercisePreviewComp(this)
    }

    override fun postRender() {
        doInPromise {
            observeValueChange(500, 250,
                    doActionFirst = true,
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

        getElemByIdAs<HTMLButtonElement>("update-submit-exercise").onSingleClickWithDisabled("Salvestan...") {
            onSaveUpdatedAdoc(getCurrentAdoc())
        }
    }

    override fun render(): String = tmRender("t-c-exercise-tab-exercise-text-edit",
            "editorDstId" to editor.dstId,
            "previewDstId" to preview.dstId,
            "doUpdateLabel" to "Salvesta"
    )

    private fun getCurrentAdoc() = editor.getFileValue(ADOC_FILENAME)

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

    override fun render(): String = tmRender("t-c-exercise-tab-exercise-text-edit-preview",
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
