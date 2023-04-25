package pages.exercise.editor.tsl

import components.code_editor.CodeEditorComp
import debug
import observeValueChange
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.getElemByIdOrNull


class TSLTabTslComp(
    private val onTslChanged: suspend (String?) -> Unit,
    parent: Component
) : Component(parent) {

    companion object {
        const val FILENAME = "tsl.json"
    }

    private var externallyChanged = false

    private val editor = CodeEditorComp(
        CodeEditorComp.File(FILENAME, ""),
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

    fun setTsl(tslJson: String) {
        editor.setFileValue(FILENAME, tslJson)
        externallyChanged = true
    }

    fun refreshEditor() = editor.refresh()
}

