package components

import Icons
import components.form.ButtonComp
import debug
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.plainDstStr
import show
import translation.Str

class EditModeButtonsComp(
    private val onModeChange: suspend (Boolean) -> Boolean,
    private val onSave: suspend () -> Boolean,
    private val postModeChange: suspend () -> Unit = {},
    private val canCancel: suspend () -> Boolean = { true },
    startAsEditing: Boolean = false,
    parent: Component?
) : Component(parent) {

    private enum class State { NOT_EDITING, EDITING }

    private var state: State = if (startAsEditing) State.EDITING else State.NOT_EDITING

    private val startEditBtn =
        ButtonComp(
            ButtonComp.Type.FLAT, Str.doChange, Icons.edit, clickedLabel = Str.loading,
            onClick = { changeEditMode(true) }, parent = this
        )
    private val cancelBtn =
        ButtonComp(
            ButtonComp.Type.FLAT, Str.cancel, Icons.close, {
                if (canCancel())
                    changeEditMode(false)
            }, parent = this
        )
    private val saveBtn =
        ButtonComp(
            ButtonComp.Type.PRIMARY, Str.doSave, Icons.check, clickedLabel = Str.saving,
            onClick = { onSave() }, parent = this
        )

    override val children = listOf(startEditBtn, cancelBtn, saveBtn)

    override fun render() = plainDstStr(startEditBtn.dstId, cancelBtn.dstId, saveBtn.dstId)

    override fun postChildrenBuilt() {
        updateBtnVisibility()
    }

    private suspend fun changeEditMode(nowEditing: Boolean) {
        val newMode = if (nowEditing) State.EDITING else State.NOT_EDITING
        if (state != newMode) {
            val isModeChangeAccepted = onModeChange(nowEditing)
            if (isModeChangeAccepted) {
                debug { "State changed to $newMode" }
                state = newMode
                updateBtnVisibility()
                postModeChange()
            } else {
                debug { "State change from $state to $newMode not accepted" }
            }
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
