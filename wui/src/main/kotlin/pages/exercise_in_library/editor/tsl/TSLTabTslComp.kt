package pages.exercise_in_library.editor.tsl

import components.code_editor.CodeEditorComp
import debug
import observeValueChange
import pages.exercise_in_library.editor.AutoassessEditorComp.Companion.TSL_SPEC_FILENAME_JSON
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.getElemByIdOrNull


class TSLTabTslComp(
    private val tslSpec: String,
    private val onTslChanged: suspend (String?) -> Unit,
    parent: Component
) : Component(parent) {

    private var externallyChanged = false

    private val editor = CodeEditorComp(
        CodeEditorComp.File(TSL_SPEC_FILENAME_JSON, tslSpec),
        showTabs = false,
        parent = this,
    )

    override val children: List<Component>
        get() = listOf(editor)

    override fun postRender() {
        doInPromise {
            observeValueChange(500, 250,
                valueProvider = { editor.getActiveTabContent() },
                continuationConditionProvider = { getElemByIdOrNull(editor.dstId) != null },
                action = {
                    if (externallyChanged) {
                        debug { "TSL changed from UI" }
                        externallyChanged = false
                    } else {
                        debug { "TSL changed from editor" }
                        onTslChanged(it)
                    }
                }
            )
        }
    }

    fun getTsl() = editor.getActiveTabContent().orEmpty()

    fun setTsl(tslSpec: String) {
        editor.setFileValue(TSL_SPEC_FILENAME_JSON, tslSpec)
        externallyChanged = true
    }

    fun setEditable(nowEditable: Boolean) {
        editor.setFileEditable(TSL_SPEC_FILENAME_JSON, nowEditable)
    }

    fun refreshEditor() = editor.refresh()
}

