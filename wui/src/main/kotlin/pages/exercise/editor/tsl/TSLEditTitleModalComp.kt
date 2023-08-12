package pages.exercise.editor.tsl

import Str
import components.form.StringFieldComp
import components.form.validation.StringConstraints
import components.modal.BinaryModalComp
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise

class TSLEditTitleModalComp(
    private val onTitleChanged: (String) -> Unit,
    parent: Component,
) : Component(parent) {

    var title: String = ""
        set(value) {
            field = value
            nameField.setValue(value)
        }

    private val modalComp: BinaryModalComp<Unit> = BinaryModalComp(
        Str.doEditTitle(), Str.doSave(), Str.cancel(), Str.saving(),
        defaultReturnValue = Unit,
        primaryAction = { onTitleChanged(nameField.getValue()) }, //primaryPostAction = ::reinitialise,
        onOpen = { nameField.focus() }, parent = this
    )

    private val nameField = StringFieldComp(
        "Testi pealkiri",
        true,
        initialValue = title,
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

    private fun updateSubmitBtn(isValid: Boolean) {
        modalComp.primaryButton.setEnabled(isValid)
    }
}