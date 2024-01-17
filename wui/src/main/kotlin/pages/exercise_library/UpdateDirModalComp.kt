package pages.exercise_library

import components.form.StringFieldComp
import components.form.validation.StringConstraints
import components.modal.BinaryModalComp
import dao.LibraryDirDAO
import kotlinx.coroutines.await
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import translation.Str

class UpdateDirModalComp(
    parent: Component,
) : Component(parent) {

    lateinit var dirId: String
    var dirCurrentName: String = ""

    private val modalComp: BinaryModalComp<String?> = BinaryModalComp(
        Str.dirSettings, Str.doSave, Str.cancel, Str.saving,
        defaultReturnValue = null,
        primaryAction = { updateDir(nameField.getValue()) }, primaryPostAction = ::reinitialise,
        onOpened = { nameField.focus() },
        parent = this
    )

    private lateinit var nameField: StringFieldComp

    override val children: List<Component>
        get() = listOf(modalComp)

    override fun create() = doInPromise {
        nameField = StringFieldComp(
            Str.directoryName,
            true, initialValue = dirCurrentName,
            paintRequiredOnInput = false,
            constraints = listOf(StringConstraints.Length(max = 100)),
            onValidChange = ::updateSubmitBtn,
            onENTER = { modalComp.primaryButton.click() },
            parent = modalComp
        )

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

    private suspend fun updateDir(name: String): String {
        LibraryDirDAO.updateDir(name, dirId).await()
        return name
    }
}