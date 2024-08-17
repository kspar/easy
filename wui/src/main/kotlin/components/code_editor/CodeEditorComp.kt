package components.code_editor

import Icons
import components.DropdownIconMenuComp
import components.DropdownMenuComp
import kotlinx.coroutines.await
import kotlinx.dom.addClass
import kotlinx.dom.removeClass
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.dstIfNotNull
import rip.kspar.ezspa.getElemBySelector
import template

class CodeEditorComp(
    private var files: List<File>,
    private val placeholder: String = "",
    private val tabs: Boolean = true,
    private val lineNumbers: Boolean = true,
    private val softWrap: Boolean = false,
    private val canCreateNewFiles: Boolean = false,
    private val menuOptions: List<DropdownMenuComp.Item> = emptyList(),
    private val headerVisible: Boolean = true,
    private val footerComp: Component? = null,
    parent: Component?,
) : Component(parent) {

    data class File(
        var name: String, var content: String?,
        var isEditable: Boolean = true,
        val isRenameable: Boolean = false,
        val isDeletable: Boolean = false,
        // Auto-detect based on filename if null - not sure if hardcoding is necessary
        val lang: dynamic = null
    )

    data class Status(
        val text: String = "",
        val icon: String = "",
    )


    private var editorTabs: CodeEditorTabsComp? = null
    private var dropdownMenu: DropdownIconMenuComp? = null
    private lateinit var status: CodeEditorStatusComp
    private lateinit var editor: CodeMirrorComp

    private val initialFiles = files.map { it.copy() }

    // Note: deleting the last file is allowed in principle and this will break if this happens
    private var activeFile = files.firstOrNull()
        ?: error("Files must not be empty")


    override val children: List<Component>
        get() = listOfNotNull(editorTabs, dropdownMenu, status, editor, footerComp)

    override fun create() = doInPromise {

        editorTabs = if (tabs)
            CodeEditorTabsComp(
                files.map {
                    CodeEditorTabsComp.Tab(it.name, it.isRenameable, it.isDeletable)
                },
                selectedTabName = activeFile.name,
                canCreateTabs = canCreateNewFiles,
                onTabSelected = ::switchToFile,
                onTabRenamed = ::renameFile,
                onTabDeleted = ::deleteFile,
                onCreateNewTab = ::createNewFile,
                parent = this
            )
        else null

        dropdownMenu = if (menuOptions.isNotEmpty())
            DropdownIconMenuComp(
                Icons.dotsVertical, null, menuOptions,
                parent = this
            )
        else null

        status = CodeEditorStatusComp(parent = this)

        editor = CodeMirrorComp(
            files,
            activeFile.name,
            placeholder = placeholder,
            lineNumbers = lineNumbers,
            softWrap = softWrap,
            parent = this
        )
    }

    override fun render() = template(
        """
            <ez-editor 
            {{#lineNumbers}}line-numbers{{/lineNumbers}} 
            {{#singleTab}}single-tab{{/singleTab}} 
            {{#noHeader}}no-header{{/noHeader}}
            >
                <ez-editor-header>
                    <ez-flex>
                        ${editorTabs.dstIfNotNull()}
                    </ez-flex>
                    <ez-flex>
                        $status
                        ${dropdownMenu.dstIfNotNull()}
                    </ez-flex>
                </ez-editor-header>
                <ez-editor-main>
                    $editor
                </ez-editor-main>
                <ez-editor-footer>
                    ${footerComp.dstIfNotNull()}
                </ez-editor-footer>
            </ez-editor>
        """.trimIndent(),
        "lineNumbers" to lineNumbers,
        "singleTab" to (files.size == 1 && !canCreateNewFiles),
        "noHeader" to !headerVisible,
    )

    override fun postChildrenBuilt() {
        refreshEditable()
    }

    fun getActiveFilename() = activeFile.name

    fun setActiveFilename(filename: String) {
        editorTabs?.setActiveTab(filename)
    }

    private fun switchToFile(filename: String) {
        activeFile = files.first { it.name == filename }
        editor.switchToFile(filename)
        refreshEditable()
    }

    fun getContent(filename: String? = null): String {
        val fname = filename ?: activeFile.name
        return editor.getContent(fname)
    }

    fun getAllFiles(): Map<String, String> {
        refreshContent()
        return files.associate { it.name to it.content.orEmpty() }
    }

    suspend fun setContent(content: String, filename: String? = null) {
        val file = if (filename != null)
            files.first { it.name == filename }
        else activeFile

        file.content = content
        createAndBuild().await()
    }

    fun setEditable(editable: Boolean, filename: String? = null) {
        val file = if (filename != null)
            files.first { it.name == filename }
        else activeFile

        file.isEditable = editable
        refreshEditable()
    }

    fun setActionsEnabled(enabled: Boolean) {
        editorTabs?.setCreateFileEnabled(enabled)
        dropdownMenu?.setEnabled(enabled)
    }

    fun setStatus(status: Status? = null) = doInPromise {
        if (status != null)
            this.status.set(status.text, status.icon)
        else
            this.status.set("", "")
    }


    override fun hasUnsavedChanges(): Boolean {
        refreshContent()
        return files != initialFiles
    }

    private suspend fun renameFile(old: String, new: String) {
        refreshContent()
        files.first { it.name == old }.name = new
        createAndBuild().await()
    }

    private suspend fun deleteFile(name: String) {
        files = files - files.first { it.name == name }
        if (files.none { it.name == activeFile.name })
            activeFile = files.first()
        createAndBuild().await()
    }

    private suspend fun createNewFile(name: String) {
        refreshContent()
        files = files + File(name, null, isEditable = true, isRenameable = true, isDeletable = true)
        createAndBuild().await()
    }

    private fun refreshContent() {
        files.forEach {
            it.content = editor.getContent(it.name)
        }
    }

    private fun refreshEditable() {
        val isActiveEditable = activeFile.isEditable
        val editorEl = rootElement.getElemBySelector("ez-editor")
        if (isActiveEditable)
            editorEl.removeClass("read-only")
        else
            editorEl.addClass("read-only")

        editor.setEditable(isActiveEditable)
    }
}