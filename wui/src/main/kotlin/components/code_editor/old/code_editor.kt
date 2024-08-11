package components.code_editor.old

import Icons
import components.text.LinkComp
import debug
import kotlinx.coroutines.await
import kotlinx.dom.addClass
import kotlinx.dom.clear
import kotlinx.dom.removeClass
import libheaders.CodeMirror
import libheaders.CodeMirrorInstance
import libheaders.tabHandler
import rip.kspar.ezspa.*
import strIfTrue
import template
import warn


class OldCodeEditorComp(
    // Original files, can be used later to check for changes
    private val files: List<File>,
    private val fileCreator: CreateFile? = null,
    private val softWrap: Boolean = false,
    private val placeholder: String? = null,
    private val showLineNumbers: Boolean = true,
    private val showTabs: Boolean = true,
    parent: Component?,
) : Component(parent) {

    // TODO: code editor tabs same as subpage tabs
    //  if tab name is editable / tab is removable - on active&hover, show edit icon right of name, have unsymmetrical paddings (~ left 3/right 1?)
    //  if creatable - add + tab as last
    //  check paddings etc to align with other tabs

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
        val name: String, val content: String?,
        val editability: Edit = Edit.EDITABLE,
        // Auto-detect based on filename if null
        val lang: dynamic = null
    )

    enum class Edit { EDITABLE, READONLY, TOGGLED }

    data class CreateFile(
        val newFileEditability: Edit,
        val fileLang: dynamic = null,
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

    val activeDoc
        get() = activeTab?.doc

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

    override fun render(): String = template(
        """
            <div id="{{editorId}}" 
                class="editor-wrapper {{^showLineNums}}no-gutter{{/showLineNums}} ${(tabs.size == 1).strIfTrue { "single-tab" }}">
                {{#showTabs}}
                    <div class="editor-top">
                        <div class="left-side">
                            <ez-code-edit-tabs>
                                {{#tabs}}
                                    {{{tab}}}
                                {{/tabs}}
                            </ez-code-edit-tabs>
                            {{#canCreate}}
                                <ez-icon-action id="{{createId}}">{{{createIcon}}}</ez-icon-action>
                            {{/canCreate}}
                        </div>
                        <div class="right-side">
                            <span class="top-item"><ez-dst id="{{toggleId}}"></ez-dst></span>
                        </div>
                    </div>
                {{/showTabs}}
                <textarea id="{{textareaId}}"></textarea>
                <ez-dst id="{{createModalId}}"></ez-dst>
            </div>
        """.trimIndent(),
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
                    createFileWithCreator(filename)
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

    fun setFileValue(
        filename: String, value: String?, newFileEdit: Edit = Edit.EDITABLE, newFileLang: dynamic = null
    ) {
        val existingTab = tabs.singleOrNull { it.filename == filename }
        val tab = existingTab ?: createFile(filename, newFileLang, newFileEdit)
        tab.doc.setValue(value)
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

    /**
     * Call after the editor becomes visible after it's been changed while not visible.
     */
    fun refresh() = editor.refresh()

    private fun getEditorElement() = getElemById(editorId)

    private fun fileToTab(f: File): Tab {
        val mode = f.lang ?: CodeMirror.findModeByFileName(f.name)?.mode
        return Tab(f.name, CodeMirror.Doc(f.content.orEmpty(), mode), IdGenerator.nextId(), f.editability, mode)
    }

    private fun tabToHtml(tab: Tab) = template(
        """
            <a id="{{id}}" class="top-item editor-tab" href="#!">{{name}}</a>
        """.trimIndent(),
        "id" to tab.id,
        "name" to tab.filename
    )

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

    private fun createFile(filename: String, lang: dynamic, editability: Edit): Tab {
        val file = File(filename, null, editability, lang)
        val tab = fileToTab(file)
        tabs.add(tab)
        createFileModalComp.setExistingFilenames(tabs.map { it.filename })

        getEditorElement().getElemBySelector("ez-code-edit-tabs").appendHTML(tabToHtml(tab))
        refreshTabActions()
        return tab
    }

    private fun createFileWithCreator(filename: String) {
        debug { "Creating new file: $filename" }
        fileCreator!!
        val tab = createFile(filename, fileCreator.fileLang, fileCreator.newFileEditability)
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
            LinkComp("Muuda", null, null, ::toggleEdit, this, editToggleId)

        editToggleComp?.createAndBuild()
    }

    private fun toggleEdit() {
        toggleEditEnabled = !toggleEditEnabled
        refreshEditability()
    }

    private fun removeEditToggle() {
        if (showTabs)
            getElemById(editToggleId).clear()
    }

}
