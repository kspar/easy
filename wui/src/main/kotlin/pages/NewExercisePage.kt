package pages

import MathJax
import PageName
import Role
import getContainer
import getElemById
import getElemByIdAs
import getElemByIdOrNull
import highlightCode
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import libheaders.CodeMirror
import objOf
import observeValueChange
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.get
import org.w3c.dom.set
import queries.ReqMethod
import queries.fetchEms
import queries.http200
import queries.parseTo
import tmRender
import kotlin.browser.localStorage

object NewExercisePage : EasyPage() {

    @Serializable
    data class HtmlPreview(
            val content: String
    )


    override val pageName: Any
        get() = PageName.NEW_EXERCISE

    override fun pathMatches(path: String) =
            path.matches("^/exercises/new/?$")

    override val allowedRoles: List<Role>
        get() = listOf(Role.TEACHER, Role.ADMIN)

    override fun build(pageStateStr: String?) {
        MainScope().launch {
            getContainer().innerHTML = tmRender("tm-teach-new-exercise")

            val savedAdoc = localStorage["new-exercise-adoc"] ?: ""
            val savedHtml = localStorage["new-exercise-html"] ?: ""

            val inEditor = CodeMirror.fromTextArea(getElemById("text-adoc"),
                    objOf("mode" to "asciidoc",
                            "lineNumbers" to true,
                            "autoRefresh" to true,
                            "viewportMargin" to 100))

            inEditor.setValue(savedAdoc)

            val outEditor = CodeMirror.fromTextArea(getElemById("text-html"),
                    objOf("mode" to objOf("name" to "xml", "htmlMode" to true),
                            "lineNumbers" to true,
                            "autoRefresh" to true,
                            "viewportMargin" to 100,
                            "readOnly" to true))

            CodeMirror.autoLoadMode(outEditor, "xml")
            outEditor.setValue(savedHtml)

            val statusElement = getElemByIdAs<HTMLDivElement>("status")
            statusElement.innerText = "Up to date"
            val previewElement = getElemById("exercise-text")
            previewElement.innerHTML = savedHtml
            highlightCode()
            MathJax.formatPageIfNeeded(savedHtml)

            MainScope().launch {
                observeValueChange(2000, 1000,
                        valueProvider = { inEditor.getValue() },
                        continuationConditionProvider = { getElemByIdOrNull("text-adoc") != null },
                        action = {
                            statusElement.innerText = "Refreshing..."
                            val html = adocToHtml(it)
                            previewElement.innerHTML = html
                            highlightCode()
                            MathJax.formatPageIfNeeded(html)
                            outEditor.setValue(html)
                            localStorage["new-exercise-adoc"] = it
                            localStorage["new-exercise-html"] = html
                            statusElement.innerText = "Up to date"
                        },
                        idleCallback = {
                            statusElement.innerText = "Waiting"
                        }
                )
            }
        }
    }

    private suspend fun adocToHtml(adoc: String): String {
        return fetchEms("/preview/adoc", ReqMethod.POST, mapOf("content" to adoc),
                successChecker = { http200 }).await()
                .parseTo(HtmlPreview.serializer()).await().content
    }
}