package components

import Icons
import components.form.ButtonComp
import debug
import plainDstStr
import rip.kspar.ezspa.Component
import show

class EditModeButtonsComp(
    private val onModeChanged: suspend (Boolean) -> Unit,
    private val onSave: suspend () -> Boolean,
    private val canCancel: suspend () -> Boolean = { true },
    startAsEditing: Boolean = false,
    parent: Component?
) : Component(parent) {

    private enum class State { NOT_EDITING, EDITING }

    private var state: State = if (startAsEditing) State.EDITING else State.NOT_EDITING

    private val startEditBtn =
        ButtonComp(
            ButtonComp.Type.FLAT, "Muuda", Icons.edit,
            { changeEditMode(true) }, parent = this
        )
    private val cancelBtn =
        ButtonComp(
            ButtonComp.Type.FLAT, "Tühista", Icons.close, {
                if (canCancel())
                    changeEditMode(false)
            }, parent = this
        )
    private val saveBtn =
        ButtonComp(
            ButtonComp.Type.PRIMARY, "Salvesta", Icons.check, disabledLabel = "Salvestan...",
            onClick = { onSave() }, parent = this
        )

    override val children = listOf(startEditBtn, cancelBtn, saveBtn)

    override fun render() = plainDstStr(startEditBtn.dstId, cancelBtn.dstId, saveBtn.dstId)

    override fun postChildrenBuilt() {
        updateBtnVisibility()
    }

    suspend fun changeEditMode(nowEditing: Boolean) {
        val newMode = if (nowEditing) State.EDITING else State.NOT_EDITING
        if (state != newMode) {
            debug { "State changed to $newMode" }
            state = newMode

            updateBtnVisibility()

            onModeChanged(nowEditing)
        }
    }

    fun setSaveEnabled(nowEnabled: Boolean) {
        saveBtn.setEnabled(nowEnabled)
    }

    private fun updateBtnVisibility() {
        startEditBtn.show(state == State.NOT_EDITING)
        saveBtn.show(state == State.EDITING)
        cancelBtn.show(state == State.EDITING)
    }
}
