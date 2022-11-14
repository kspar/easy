package pages.exercise_library

import Str
import components.form.StringFieldComp
import components.form.validation.StringConstraints
import components.modal.BinaryModalComp
import components.modal.Modal
import dao.LibraryDirDAO
import kotlinx.coroutines.await
import plainDstStr
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise

class CreateDirModalComp(
    private val parentDirId: String?,
    parent: Component,
) : Component(parent) {


    private val modalComp: BinaryModalComp<String?> = BinaryModalComp(
        "Uus kaust", Str.doSave(), Str.cancel(), Str.saving(),
        primaryAction = { createDir(nameField.getValue()) },
        primaryPostAction = ::reinitialise, onOpen = { nameField.focus() },
        defaultReturnValue = null, id = Modal.CREATE_DIR, parent = this
    )

    private val nameField = StringFieldComp(
        "Kausta nimi",
        true, paintRequiredOnInput = false,
        constraints = listOf(StringConstraints.Length(max = 100)),
        onValidChange = ::updateSubmitBtn,
        parent = modalComp
    )

    override val children: List<Component>
        get() = listOf(modalComp)

    override fun create() = doInPromise {
        modalComp.setContentComps { listOf(nameField) }
    }

    override fun render() = plainDstStr(modalComp.dstId)

    override fun postChildrenBuilt() {
        nameField.validateInitial()
    }

    fun openWithClosePromise() = modalComp.openWithClosePromise()

    private fun reinitialise() {
        nameField.rebuild()
        nameField.validateInitial()
    }

    private fun updateSubmitBtn(isValid: Boolean) {
        modalComp.primaryButton.setEnabled(isValid)
    }

    private suspend fun createDir(name: String): String {
        return LibraryDirDAO.createDir(name, parentDirId).await()
    }
}