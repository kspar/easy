package components.code_editor

import Icons
import components.LinkComp
import debug
import kotlinx.coroutines.await
import kotlinx.dom.addClass
import kotlinx.dom.clear
import kotlinx.dom.removeClass
import libheaders.CodeMirror
import libheaders.CodeMirrorInstance
import libheaders.tabHandler
import negation
import rip.kspar.ezspa.*
import tmRender
import warn


class CodeEditorComp(
    // Original files, can be used later to check for changes
    private val files: List<File>,
    private val fileCreator: CreateFile? = null,
    private val softWrap: Boolean = false,
    private val placeholder: String? = null,
    private val showLineNumbers: Boolean = true,
    private val showTabs: Boolean = true,
    parent: Component?,
) : Component(parent) {

    // TODO: should have a separate comp for code editor tabs (and/or toolbar) to avoid drawing new tabs like this

    // TODO: delete tabs/files

    constructor(
        file: File,
        fileCreator: CreateFile? = null,
        softWrap: Boolean = false,
        placeholder: String? = null,
        showLineNumbers: Boolean = true,
        showTabs: Boolean = true,
        parent: Component?
    ) : this(listOf(file), fileCreator, softWrap, placeholder, showLineNumbers, showTabs, parent)

    data class File(
        val name: String, val content: String?, val lang: dynamic, val editability: Edit = Edit.EDITABLE
    )

    enum class Edit { EDITABLE, READONLY, TOGGLED }

    data class CreateFile(
        val fileLang: dynamic,
        val newFileEditability: Edit,
    )

    private data class Tab(
        val filename: String,
        val doc: CodeMirror.Doc,
        val id: String,
        var editability: Edit,
        val lang: dynamic
    )

    private val tabs: MutableList<Tab>
    private val editorId = IdGenerator.nextId()
    private val textareaId = IdGenerator.nextId()
    private val createFileId = IdGenerator.nextId()
    private val editToggleId = IdGenerator.nextId()
    private var activeTab: Tab?
    private var toggleEditEnabled = false

    private lateinit var editor: CodeMirrorInstance

    private var editToggleComp: LinkComp? = null
    private val createFileModalComp: CreateFileModalComp

    init {
        files.groupingBy { it.name }.eachCount().forEach { (filename, count) ->
            if (count > 1) {
                warn { "Non-unique filename: $filename (count: $count)" }
            }
        }

        tabs = files.map { fileToTab(it) }.toMutableList()
        activeTab = tabs.getOrNull(0)

        createFileModalComp = CreateFileModalComp(tabs.map { it.filename }, this)
    }

    override val children: List<Component>
        get() = listOfNotNull(editToggleComp, createFileModalComp)

    override fun render(): String = tmRender(
        "t-c-code-editor",
        "editorId" to editorId,
        "textareaId" to textareaId,
        "toggleId" to editToggleId,
        "showLineNums" to showLineNumbers,
        "showTabs" to showTabs,
        "tabs" to if (showTabs) tabs.map {
            mapOf("tab" to tabToHtml(it))
        } else emptyList(),
        "canCreate" to (fileCreator != null),
        "createId" to createFileId,
        "createIcon" to Icons.add,
        "createModalId" to createFileModalComp.dstId,
    )

    override fun postRender() {
        editor = CodeMirror.fromTextArea(
            getElemById(textareaId),
            objOf(
                "lineNumbers" to showLineNumbers,
                "lineWrapping" to softWrap,
                "autoRefresh" to true,
                "viewportMargin" to 100,
                "theme" to "idea",
                "indentUnit" to 4,
                "matchBrackets" to true,
                "extraKeys" to tabHandler,
                "undoDepth" to 500,
                "cursorScrollMargin" to 1,
                "placeholder" to placeholder,
            )
        )

        activeTab?.let {
            switchToTab(it)
        }

        refreshTabActions()

        // TODO: don't allow creating duplicate filenames
        if (fileCreator != null && showTabs) {
            getElemById(createFileId).onVanillaClick(true) {
                createFileModalComp.setExistingFilenames(tabs.map { it.filename })
                val filename = createFileModalComp.openWithClosePromise().await()
                if (filename != null) {
                    createFile(filename)
                }
            }
        }
    }

    override fun hasUnsavedChanges(): Boolean {
        val origFiles = files.associate { it.name to it.content.orEmpty() }
        val editedFiles = getAllFiles().associate { it.name to it.content.orEmpty() }
        return origFiles != editedFiles
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

    fun getActiveTabFilename() = activeTab?.filename

    fun getActiveTabContent() = getActiveTabFilename()?.let { getFileValue(it) }

    fun setActiveTabByFilename(filename: String) {
        val tab = tabs.firstOrNull { it.filename == filename }
        if (tab != null) {
            switchToTab(tab)
        } else {
            warn { "Code editor tab with id $filename not found" }
        }
    }

    private fun getEditorElement() = getElemById(editorId)

    private fun fileToTab(f: File): Tab =
        Tab(f.name, CodeMirror.Doc(f.content.orEmpty(), f.lang), IdGenerator.nextId(), f.editability, f.lang)

    private fun tabToHtml(tab: Tab) =
        tmRender("t-code-editor-tab", mapOf("id" to tab.id, "name" to tab.filename))

    private fun refreshTabActions() {
        tabs.forEach { CodeMirror.autoLoadMode(editor, it.lang) }

        if (showTabs) {
            tabs.forEach { tab ->
                getElemById(tab.id).onVanillaClick(true) {
                    switchToTab(tab)
                }
            }
        }
    }

    private fun createFile(filename: String) {
        debug { "Creating new file: $filename" }
        fileCreator!!

        val file = File(filename, null, fileCreator.fileLang, fileCreator.newFileEditability)
        val tab = fileToTab(file)
        tabs.add(tab)
        createFileModalComp.setExistingFilenames(tabs.map { it.filename })

        getEditorElement().getElemBySelector("ez-code-edit-tabs").appendHTML(tabToHtml(tab))

        refreshTabActions()
        switchToTab(tab)
    }

    private fun switchToTab(tab: Tab) {
        val previousTab = activeTab
        activeTab = tab

        if (showTabs) {
            if (previousTab != null)
                getElemById(previousTab.id).apply {
                    removeClass("active")
                    setAttribute("href", "#!")
                }

            getElemById(tab.id).apply {
                addClass("active")
                removeAttribute("href")
            }
        }

        editor.swapDoc(tab.doc)

        refreshEditability()
    }

    private fun refreshEditability() {
        activeTab?.let {
            when (it.editability) {
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
        if (!showTabs) {
            // Toggle is located in tabs top bar
            warn { "Tabs are disabled, toggling won't work" }
            return
        }

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
        if (showTabs)
            getElemById(editToggleId).clear()
    }

}
