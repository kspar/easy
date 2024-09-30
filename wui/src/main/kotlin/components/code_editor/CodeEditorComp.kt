package components.code_editor

import Icons
import components.code_editor.parts.CodeEditorStatusComp
import components.code_editor.parts.CodeEditorTabsComp
import components.code_editor.parts.CodeMirrorComp
import components.dropdown.DropdownIconMenuComp
import components.dropdown.DropdownMenuComp
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
    private val onFileSwitch: (filename: String) -> Unit = {},
    parent: Component?,
) : Component(parent) {

    data class File(
        var name: String, var content: String?,
        var isEditable: Boolean = true,
        var isRenameable: Boolean = false,
        var isDeletable: Boolean = false,
        // Auto-detect based on filename if null - not sure if hardcoding is necessary
        val lang: dynamic = null,
        val startActive: Boolean = false,
    )


    private var editorTabs: CodeEditorTabsComp? = null
    private var dropdownMenu: DropdownIconMenuComp? = null
    private lateinit var status: CodeEditorStatusComp
    private lateinit var editor: CodeMirrorComp

    // Copy and convert nulls to empty for change checking
    private val initialFiles = files.map { it.copy(content = it.content.orEmpty()) }

    // Note: deleting the last file is allowed in principle and this will break if this happens
    private var activeFile = files.firstOrNull { it.startActive } ?: files.firstOrNull()
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
        onFileSwitch(filename)
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
        val file = if (filename != null) {

            // Create file if doesn't exist
            files.firstOrNull { it.name == filename }
                ?: createNewFile(filename)

        } else activeFile

        file.content = content
        createAndBuild().await()
    }

    suspend fun setFileProps(
        editable: Boolean? = null, renameable: Boolean? = null, deletable: Boolean? = null,
        filename: String? = null
    ) {
        val file = if (filename != null)
            files.first { it.name == filename }
        else activeFile


        editable?.let {
            file.isEditable = editable
        }
        renameable?.let {
            file.isRenameable = renameable
        }
        deletable?.let {
            file.isDeletable = deletable
        }

        refreshContent()
        createAndBuild().await()
    }

    fun setActionsEnabled(enabled: Boolean) {
        editorTabs?.setCreateFileEnabled(enabled)
        dropdownMenu?.setEnabled(enabled)
    }

    fun setStatusText(text: String = "") = doInPromise {
        status.set(text = text)
    }

    fun setStatusIcon(icon: String = "") = doInPromise {
        status.set(icon = icon)
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

    private suspend fun createNewFile(name: String): File {
        refreshContent()
        val newFile = File(name, null, isEditable = true, isRenameable = true, isDeletable = true)
        files = files + newFile
        createAndBuild().await()
        return newFile
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