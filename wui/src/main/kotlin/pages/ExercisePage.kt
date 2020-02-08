package pages

import DateSerializer
import MathJax
import PageName
import Role
import Str
import getContainer
import getElemById
import getElemByIdAs
import getElemByIdOrNull
import getNodelistBySelector
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import libheaders.CodeMirror
import libheaders.CodeMirrorInstance
import libheaders.highlightCode
import objOf
import observeValueChange
import onVanillaClick
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLButtonElement
import queries.ReqMethod
import queries.fetchEms
import queries.http200
import queries.parseTo
import successMessage
import tmRender
import kotlin.browser.window
import kotlin.dom.addClass
import kotlin.dom.removeClass
import kotlin.js.Date

object ExercisePage : EasyPage() {

    @Serializable
    data class Exercise(
            @Serializable(with = DateSerializer::class)
            val created_at: Date,
            val is_public: Boolean,
            val owner_id: String,
            @Serializable(with = DateSerializer::class)
            val last_modified: Date,
            val last_modified_by_id: String,
            val grader_type: GraderType,
            val title: String,
            val text_html: String? = null,
            val text_adoc: String? = null,
            val grading_script: String? = null,
            val container_image: String? = null,
            val max_time_sec: Int? = null,
            val max_mem_mb: Int? = null,
            val assets: List<Asset>? = null,
            val executors: List<Executor>? = null,
            val on_courses: List<OnCourse>
    )

    @Serializable
    data class Asset(
            val file_name: String,
            val file_content: String
    )

    @Serializable
    data class Executor(
            val id: String,
            val name: String
    )

    @Serializable
    data class OnCourse(
            val id: String,
            val title: String,
            val course_exercise_id: String,
            val course_exercise_title_alias: String?
    )

    enum class GraderType {
        AUTO, TEACHER
    }

    @Serializable
    data class HtmlPreview(
            val content: String
    )


    override val pageName: Any
        get() = PageName.EXERCISE

    override val allowedRoles: List<Role>
        get() = listOf(Role.ADMIN)

    override fun pathMatches(path: String): Boolean =
            path.matches("^/exercises/\\w+/details/?$")

    override fun build(pageStateStr: String?) {
        val exerciseId = extractSanitizedExerciseId(window.location.pathname)

        MainScope().launch {

            val resp = fetchEms("/exercises/$exerciseId", ReqMethod.GET,
                    successChecker = { http200 }).await()
            val exercise = resp.parseTo(Exercise.serializer()).await()

            getContainer().innerHTML = tmRender("tm-modify-global-exercise", mapOf(
                    "title" to exercise.title,
                    "onCoursesLabel" to Str.usedOnCoursesLabel(),
                    "onCourses" to exercise.on_courses.map { mapOf("title" to it.title) },
                    "previewLabel" to Str.previewLabel(),
                    "doUpdateLabel" to Str.doSave()
            ))

            val textEditor = CodeMirror.fromTextArea(getElemById("text-editor"),
                    objOf("mode" to "asciidoc",
                            "lineNumbers" to true,
                            "autoRefresh" to true,
                            "viewportMargin" to 100))

            val adocDoc = CodeMirror.Doc(exercise.text_adoc ?: "", "asciidoc")
            textEditor.swapDoc(adocDoc)
            val htmlDoc = CodeMirror.Doc(exercise.text_html ?: "", objOf("name" to "xml", "htmlMode" to true))
            CodeMirror.autoLoadMode(textEditor, "xml")

            val htmlTab = getElemByIdAs<HTMLAnchorElement>("tab-html")
            val adocTab = getElemByIdAs<HTMLAnchorElement>("tab-adoc")
            htmlTab.onVanillaClick(true) {
                switchTextEditorTab(htmlTab, adocTab, textEditor, htmlDoc, true)
            }
            adocTab.onVanillaClick(true) {
                switchTextEditorTab(adocTab, htmlTab, textEditor, adocDoc, false)
            }

            val updateButton = getElemByIdAs<HTMLButtonElement>("update-submit")
            updateButton.onVanillaClick(true) {
                MainScope().launch {
                    updateButton.disabled = true
                    updateButton.textContent = Str.saving()
                    updateExercise(exerciseId, exercise, adocDoc.getValue())
                    updateButton.disabled = false
                    updateButton.textContent = Str.doSave()
                    successMessage { Str.exerciseSaved() }
                }
            }

            val statusElement = getElemById("status")
            val previewElement = getElemById("exercise-text")
            observeValueChange(1000, 500,
                    doActionFirst = true,
                    valueProvider = { adocDoc.getValue() },
                    continuationConditionProvider = { getElemByIdOrNull("text-editor") != null },
                    action = {
                        statusElement.textContent = "Uuendan eelvaadet..."
                        val html = adocToHtml(it)
                        previewElement.innerHTML = html
                        highlightCode()
                        MathJax.formatPageIfNeeded(html)
                        htmlDoc.setValue(html)
                        statusElement.textContent = "Up-to-date"
                    },
                    idleCallback = {
                        statusElement.textContent = "Ootan..."
                    }
            )
        }
    }

    private suspend fun updateExercise(exerciseId: String, oldExercise: Exercise, newTextAdoc: String) {
        val body = oldExercise.let {
            mapOf(
                    "title" to it.title,
                    "text_adoc" to newTextAdoc,
                    "public" to it.is_public,
                    "grader_type" to it.grader_type.name,
                    "grading_script" to it.grading_script,
                    "container_image" to it.container_image,
                    "max_time_sec" to it.max_time_sec,
                    "max_mem_mb" to it.max_mem_mb,
                    "assets" to it.assets?.map { mapOf("file_name" to it.file_name, "file_content" to it.file_content) },
                    "executors" to it.executors?.map { mapOf("executor_id" to it.id) }
            )
        }
        fetchEms("/exercises/$exerciseId", ReqMethod.PUT, body, successChecker = { http200 }).await()
    }

    private fun switchTextEditorTab(activeTab: HTMLAnchorElement, inactiveTab: HTMLAnchorElement,
                                    textEditor: CodeMirrorInstance, newDoc: CodeMirror.Doc, readOnly: Boolean) {
        activeTab.addClass("active")
        activeTab.removeAttribute("href")
        inactiveTab.removeClass("active")
        inactiveTab.setAttribute("href", "#!")
        textEditor.swapDoc(newDoc)
        textEditor.setOption("readOnly", readOnly)
    }

    private fun highlightCode() {
        getNodelistBySelector("pre.highlightjs.highlight code.hljs").highlightCode()
    }

    private suspend fun adocToHtml(adoc: String): String {
        val resp = fetchEms("/preview/adoc", ReqMethod.POST, mapOf("content" to adoc),
                successChecker = { http200 }).await()
        return resp.parseTo(HtmlPreview.serializer()).await().content
    }

    private fun extractSanitizedExerciseId(path: String): String {
        val match = path.match("^/exercises/(\\w+)/details/?$")
        if (match != null && match.size == 2) {
            return match[1]
        } else {
            error("Unexpected match on path: ${match?.joinToString()}")
        }
    }

}