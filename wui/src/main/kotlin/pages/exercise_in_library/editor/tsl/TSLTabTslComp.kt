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
) : Component() {

    private var externallyChanged = false

    private val editor = CodeEditorComp(
        listOf(CodeEditorComp.File(TSL_SPEC_FILENAME_JSON, tslSpec)),
        tabs = false,
        headerVisible = false,
        parent = this,
    )

    override val children: List<Component>
        get() = listOf(editor)

    override fun postChildrenBuilt() {
        doInPromise {
            observeValueChange(500, 250,
                valueProvider = { editor.getContent() },
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

    fun getTsl() = editor.getContent()

    suspend fun setTsl(tslSpec: String) {
        editor.setContent(tslSpec)
        externallyChanged = true
    }

    suspend fun setEditable(nowEditable: Boolean) {
        editor.setFileProps(editable = nowEditable)
    }
}
