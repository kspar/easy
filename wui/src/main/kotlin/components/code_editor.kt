package components

import IdGenerator
import getElemById
import getElemsByClass
import libheaders.CodeMirror
import libheaders.CodeMirrorInstance
import negation
import objOf
import onVanillaClick
import Component
import tmRender
import kotlinx.dom.addClass
import kotlinx.dom.clear
import kotlinx.dom.removeClass


class CodeEditorComp(
        files: List<File>,
        parent: Component?
) : Component(parent) {

    constructor(file: File, parent: Component?) : this(listOf(file), parent)

    data class File(val name: String, val content: String?, val lang: dynamic, val editability: Edit = Edit.EDITABLE)
    enum class Edit { EDITABLE, READONLY, TOGGLED }

    private data class Tab(val filename: String, val doc: CodeMirror.Doc, val id: String, var editability: Edit, val lang: dynamic)

    private val tabs: List<Tab>
    private val textareaId = IdGenerator.nextId()
    private val editToggleId = IdGenerator.nextId()
    private var activeTab: Tab
    private var toggleEditEnabled = false

    private lateinit var editor: CodeMirrorInstance
    private var editToggleComp: LinkComp? = null

    init {
        if (files.isEmpty())
            throw IllegalStateException("Editor must have at east one file")

        files.groupingBy { it.name }.eachCount().forEach { (filename, count) ->
            if (count > 1) {
                throw IllegalStateException("Non-unique filename: $filename (count: $count)")
            }
        }

        tabs = files.map {
            Tab(it.name, CodeMirror.Doc(it.content.orEmpty(), it.lang), IdGenerator.nextId(), it.editability, it.lang)
        }
        activeTab = tabs[0]
    }

    override val children: List<Component>
        get() = listOfNotNull(editToggleComp)

    override fun render(): String = tmRender("t-c-code-editor",
            "textareaId" to textareaId,
            "toggleId" to editToggleId,
            "tabs" to tabs.map {
                mapOf("id" to it.id, "name" to it.filename)
            }
    )

    override fun postRender() {
        editor = CodeMirror.fromTextArea(getElemById(textareaId),
                objOf("lineNumbers" to true,
                        "autoRefresh" to true,
                        "viewportMargin" to 100,
                        "theme" to "idea"
                ))

        tabs.forEach { CodeMirror.autoLoadMode(editor, it.lang) }

        switchToTab()

        tabs.forEach { tab ->
            getElemById(tab.id).onVanillaClick(true) {
                switchToTab(tab)
            }
        }
    }

    fun getAllFiles(): List<File> = tabs.map { File(it.filename, it.doc.getValue(), it.lang, it.editability) }

    fun getFileValue(filename: String): String = tabs.single { it.filename == filename }.doc.getValue()

    fun setFileValue(filename: String, value: String?) {
        tabs.single { it.filename == filename }.doc.setValue(value)
    }

    fun setFileEditable(filename: String, isEditable: Boolean) {
        tabs.single { it.filename == filename }.editability = if (isEditable) Edit.EDITABLE else Edit.READONLY
        refreshEditability()
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

        refreshEditability()
    }

    private fun refreshEditability() {
        when (activeTab.editability) {
            Edit.EDITABLE -> {
                setEditable(true)
                removeEditToggle()
            }
            Edit.READONLY -> {
                setEditable(false)
                removeEditToggle()
            }
            Edit.TOGGLED -> {
                setEditable(toggleEditEnabled)
                addEditToggle(toggleEditEnabled)
            }
        }
    }

    private fun setEditable(isEditable: Boolean) {
        val editorWrapEl = getElemById(dstId).getElemsByClass("editor-wrapper").single()
        editor.setOption("readOnly", !isEditable)
        if (isEditable)
            editorWrapEl.removeClass("editor-read-only")
        else
            editorWrapEl.addClass("editor-read-only")
    }

    private fun addEditToggle(isCurrentlyEditable: Boolean) {
        editToggleComp = if (isCurrentlyEditable)
            LinkComp("Keela muutmine", null, null, ::toggleEdit, this, editToggleId)
        else
            LinkComp("Muuda", "create", null, ::toggleEdit, this, editToggleId)

        editToggleComp?.createAndBuild()
    }

    private fun toggleEdit() {
        toggleEditEnabled = toggleEditEnabled.negation
        refreshEditability()
    }

    private fun removeEditToggle() {
        getElemById(editToggleId).clear()
    }

}
