package components.code_editor.parts

import Icons
import components.DropdownMenuComp
import components.IconButtonComp
import components.TabID
import components.TabsComp
import components.form.OldButtonComp
import components.modal.ConfirmationTextModalComp
import components.text.StringComp
import kotlinx.coroutines.await
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.dstIfNotNull
import template
import translation.Str

class CodeEditorTabsComp(
    private val tabs: List<Tab>,
    private val selectedTabName: String,
    private val canCreateTabs: Boolean,
    private val onTabSelected: (name: String) -> Unit,
    private val onTabRenamed: suspend (old: String, new: String) -> Unit,
    private val onTabDeleted: suspend (name: String) -> Unit,
    private val onCreateNewTab: suspend (name: String) -> Unit,
    parent: Component?,
) : Component(parent) {

    data class Tab(
        val title: String,
        val isRenameable: Boolean,
        val isDeletable: Boolean,
        val id: TabID = IdGenerator.nextId(),
    )

    private lateinit var tabsComp: TabsComp
    private var createTabBtn: IconButtonComp? = null
    private lateinit var createOrRenameTabModalComp: CodeEditorRenameTabModal
    private lateinit var deleteTabModalComp: ConfirmationTextModalComp


    override val children: List<Component>
        get() = listOfNotNull(tabsComp, createTabBtn, createOrRenameTabModalComp, deleteTabModalComp)

    override fun create() = doInPromise {
        tabsComp = TabsComp(
            TabsComp.Type.SECONDARY,
            tabs.map { tab ->
                TabsComp.Tab(
                    tab.title,
                    active = tab.title == selectedTabName,
                    compProvider = null,
                    menuOptions = buildList {

                        if (tab.isRenameable)
                            add(DropdownMenuComp.Item(Str.doRename, Icons.editUnf, onSelected = {

                                val newTitle = createOrRenameTabModalComp.openAndWait(
                                    tab.title,
                                    bannedFilenames = tabs.map { it.title } - tab.title
                                )
                                if (newTitle != null && newTitle != tab.title)
                                    onTabRenamed(tab.title, newTitle)
                            }))

                        if (tab.isDeletable)
                            add(DropdownMenuComp.Item(Str.doDelete, Icons.deleteUnf, onSelected = {

                                deleteTabModalComp.setText(
                                    StringComp.boldTriple(Str.doDeleteFile + " ", tab.title, "?")
                                )
                                val doDelete = deleteTabModalComp.openWithClosePromise().await()
                                if (doDelete)
                                    onTabDeleted(tab.title)
                            }))
                    },
                    id = tab.id
                )
            },
            onTabActivate = {
                onTabSelected(it.title)
            },
            parent = this
        )

        createTabBtn = if (canCreateTabs)
            IconButtonComp(
                Icons.add, null,
                onClick = {
                    createOrRenameTabModalComp.openAndWait(
                        "",
                        bannedFilenames = tabs.map { it.title }
                    )?.let {
                        onCreateNewTab(it)
                    }
                },
                parent = this
            ) else null

        createOrRenameTabModalComp = CodeEditorRenameTabModal(parent = this)

        deleteTabModalComp = ConfirmationTextModalComp(
            "", Str.doDelete, Str.cancel, Str.deleting,
            primaryAction = { true },
            primaryBtnType = OldButtonComp.Type.DANGER,
            parent = this
        )
    }

    override fun render() = template(
        """
            <ez-editor-files>
                <ez-editor-tabs>
                    $tabsComp
                </ez-editor-tabs>
                <ez-editor-create-file>
                    ${createTabBtn.dstIfNotNull()}
                </ez-editor-create-file>
            </ez-editor-tabs>
            $createOrRenameTabModalComp
            $deleteTabModalComp
        """.trimIndent(),
    )

    fun setCreateFileEnabled(enabled: Boolean) {
        createTabBtn?.setEnabled(enabled)
    }

    fun setActiveTab(tabTitle: String) {
        val id = tabs.first { it.title == tabTitle }.id
        tabsComp.activateTab(id)
    }
}