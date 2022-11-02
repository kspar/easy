package components

import Icons
import components.form.ButtonComp
import debug
import kotlinx.dom.addClass
import kotlinx.dom.removeClass
import plainDstStr
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.getElemById

class EditModeButtonsComp(
    private val onModeChanged: suspend (Boolean) -> Unit,
    private val onSave: suspend () -> Boolean,
    startAsEditing: Boolean = false,
    parent: Component?
) : Component(parent) {

    private enum class State { NOT_EDITING, EDITING }

    private var state: State = if (startAsEditing) State.EDITING else State.NOT_EDITING

    private val startEditBtn =
        ButtonComp(
            ButtonComp.Type.FLAT, Icons.edit + "Muuda", { changeEditMode(true) }, parent = this
        )
    private val cancelBtn =
        ButtonComp(
            ButtonComp.Type.FLAT, "Tühista", { changeEditMode(false) }, parent = this
        )
    private val saveBtn =
        ButtonComp(
            ButtonComp.Type.PRIMARY, "Salvesta", disabledLabel = "Salvestan...", onClick = ::saveHandler, parent = this
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

    private fun updateBtnVisibility() {
        startEditBtn.show(state == State.NOT_EDITING)
        saveBtn.show(state == State.EDITING)
        cancelBtn.show(state == State.EDITING)
    }

    private suspend fun saveHandler() {
        if (onSave())
            changeEditMode(false)
    }
}

fun Component.hide() {
    getElemById(dstId).addClass("display-none")
}

fun Component.show(show: Boolean = true) {
    if (show)
        getElemById(dstId).removeClass("display-none")
    else
        hide()
}
