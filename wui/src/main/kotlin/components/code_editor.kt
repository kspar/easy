package components

import IdGenerator
import getElemById
import getElemsByClass
import libheaders.CodeMirror
import libheaders.CodeMirrorInstance
import objOf
import onVanillaClick
import spa.Component
import tmRender
import kotlin.dom.addClass
import kotlin.dom.removeClass


class CodeEditorComp(
        files: List<File>,
        parent: Component?
) : Component(parent) {

    data class File(val name: String, val content: String?, val lang: dynamic, val isEditable: Boolean = true)
    private data class Tab(val filename: String, val doc: CodeMirror.Doc, val id: String, val isEditable: Boolean, val lang: dynamic)

    private val tabs: List<Tab>
    private var activeTab: Tab
    private val textareaId = IdGenerator.nextId()
    private lateinit var editor: CodeMirrorInstance

    init {
        if (files.isEmpty())
            throw IllegalStateException("Editor must have at east one file")

        files.groupingBy { it.name }.eachCount().forEach { (filename, count) ->
            if (count > 1) {
                throw IllegalStateException("Non-unique filename: $filename (count: $count)")
            }
        }

        tabs = files.map {
            Tab(it.name, CodeMirror.Doc(it.content.orEmpty(), it.lang), IdGenerator.nextId(), it.isEditable, it.lang)
        }
        activeTab = tabs[0]
    }

    override fun render(): String = tmRender("t-c-code-editor",
            "textareaId" to textareaId,
            "tabs" to tabs.map {
                mapOf("id" to it.id, "name" to it.filename)
            }
    )

    override fun postRender() {
        editor = CodeMirror.fromTextArea(getElemById(textareaId),
                objOf("lineNumbers" to true,
                        "autoRefresh" to true,
                        "viewportMargin" to 100))

        tabs.forEach { CodeMirror.autoLoadMode(editor, it.lang) }

        switchToTab()

        tabs.forEach { tab ->
            getElemById(tab.id).onVanillaClick(true) {
                switchToTab(tab)
            }
        }
    }

    fun getFileValue(filename: String): String = tabs.single { it.filename == filename }.doc.getValue()

    fun setFileValue(filename: String, value: String?) {
        tabs.single { it.filename == filename }.doc.setValue(value)
    }

    private fun switchToTab(tab: Tab = activeTab) {
        val previousTab = activeTab
        activeTab = tab

        getElemById(previousTab.id).apply {
            removeClass("active")
            setAttribute("href", "#!")
        }

        getElemById(tab.id).apply {
            addClass("active")
            removeAttribute("href")
        }

        editor.swapDoc(tab.doc)

        val editorWrapEl = getElemById(dstId).getElemsByClass("editor-wrapper").single()
        if (activeTab.isEditable) {
            editor.setOption("readOnly", false)
            editorWrapEl.removeClass("no-cursor")
        } else {
            editor.setOption("readOnly", true)
            editorWrapEl.addClass("no-cursor")
        }
    }

}
