package components.code_editor

import Str
import components.form.StringFieldComp
import components.form.validation.StringConstraints
import components.modal.BinaryModalComp
import plainDstStr
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise

class CreateFileModalComp(
    parent: Component,
) : Component(parent) {

    private val modalComp: BinaryModalComp<String?> = BinaryModalComp(
        "Uus fail", Str.doSave(), Str.cancel(),
        primaryAction = { filenameField.getValue() },
        primaryPostAction = ::reinitialise, onOpen = { filenameField.focus() },
        defaultReturnValue = null, parent = this
    )

    private val filenameField = StringFieldComp(
        "Faili nimi",
        true, paintRequiredOnInput = false,
        constraints = listOf(StringConstraints.Length(max = 50)),
        onValidChange = ::updateSubmitBtn,
        parent = modalComp
    )

    override val children: List<Component>
        get() = listOf(modalComp)

    override fun create() = doInPromise {
        modalComp.setContentComps { listOf(filenameField) }
    }

    override fun render() = plainDstStr(modalComp.dstId)

    override fun postChildrenBuilt() {
        filenameField.validateInitial()
    }

    fun openWithClosePromise() = modalComp.openWithClosePromise()

    private fun reinitialise() {
        filenameField.rebuild()
        filenameField.validateInitial()
    }

    private fun updateSubmitBtn(isValid: Boolean) {
        modalComp.primaryButton.setEnabled(isValid)
    }
}