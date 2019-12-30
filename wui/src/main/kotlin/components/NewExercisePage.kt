package components

import Auth
import PageName
import ReqMethod
import Role
import Str
import errorMessage
import fetchEms
import getContainer
import getElemById
import getElemByIdAs
import getElemByIdOrNull
import getNodelistBySelector
import http200
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import libheaders.CodeMirror
import libheaders.MathJax
import libheaders.highlightCode
import objOf
import observeValueChange
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.get
import org.w3c.dom.set
import parseTo
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

    override fun build(pageStateStr: String?) {
        MainScope().launch {
            if (Auth.activeRole != Role.ADMIN && Auth.activeRole != Role.TEACHER) {
                errorMessage { Str.noPermissionForPage() }
                error("User is not admin nor teacher")
            }

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
            MathJax.typeset()

            MainScope().launch {
                observeValueChange(2000, 1000,
                        valueProvider = { inEditor.getValue() },
                        continuationConditionProvider = { getElemByIdOrNull("text-adoc") != null },
                        action = {
                            statusElement.innerText = "Refreshing..."
                            val html = adocToHtml(it)
                            previewElement.innerHTML = html
                            highlightCode()
                            MathJax.typeset()
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

    private fun highlightCode() {
        getNodelistBySelector("pre.highlightjs.highlight code.hljs").highlightCode()
    }

    private suspend fun adocToHtml(adoc: String): String {
        val resp = fetchEms("/preview/adoc", ReqMethod.POST, mapOf("content" to adoc)).await()
        if (!resp.http200) {
            errorMessage { Str.somethingWentWrong() }
            error("Fetching preview failed with status ${resp.status}")
        }

        return resp.parseTo(HtmlPreview.serializer()).await().content
    }
}