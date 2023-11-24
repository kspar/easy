package pages.exercise_library

import components.form.StringFieldComp
import components.form.validation.StringConstraints
import components.modal.BinaryModalComp
import dao.LibraryDirDAO
import kotlinx.coroutines.await
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import translation.Str

class CreateDirModalComp(
    private val parentDirId: String?,
    parent: Component,
) : Component(parent) {


    private val modalComp: BinaryModalComp<String?> = BinaryModalComp(
        Str.newDirectory, Str.doSave, Str.cancel, Str.saving,
        defaultReturnValue = null,
        primaryAction = { createDir(nameField.getValue()) }, primaryPostAction = ::reinitialise,
        onOpen = { nameField.focus() }, parent = this
    )

    private val nameField = StringFieldComp(
        Str.directoryName,
        true, paintRequiredOnInput = false,
        constraints = listOf(StringConstraints.Length(max = 100)),
        onValidChange = ::updateSubmitBtn,
        onENTER = { modalComp.primaryButton.click() },
        parent = modalComp
    )

    override val children: List<Component>
        get() = listOf(modalComp)

    override fun create() = doInPromise {
        modalComp.setContentComps { listOf(nameField) }
    }

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