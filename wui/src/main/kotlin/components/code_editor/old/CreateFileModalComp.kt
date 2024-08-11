package components.code_editor.old

import components.form.StringFieldComp
import components.form.validation.ConstraintViolation
import components.form.validation.FieldConstraint
import components.form.validation.StringConstraints
import components.modal.BinaryModalComp
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.plainDstStr
import translation.Str

class CreateFileModalComp(
    existingFilenames: List<String>,
    parent: Component,
) : Component(parent) {

    private var disallowedInputs: List<String> = setExistingFilenames(existingFilenames)

    private val nonDuplicateNameConstraint: FieldConstraint<String> = object : FieldConstraint<String>() {
        override fun validate(value: String, fieldNameForMessage: String): ConstraintViolation<String>? {
            return when {
                disallowedInputs.contains(value.lowercase()) -> violation("Selle nimega fail juba eksisteerib")
                else -> null
            }
        }
    }

    // FIXME: does not allow multiple code editors on same page due to constant id
    private val modalComp: BinaryModalComp<String?> = BinaryModalComp(
        "Uus fail", Str.doSave, Str.cancel,
        defaultReturnValue = null,
        primaryAction = { filenameField.getValue() }, primaryPostAction = ::reinitialise,
        onOpened = { filenameField.focus() }, parent = this
    )

    private val filenameField = StringFieldComp(
        "Faili nimi",
        true, paintRequiredOnInput = false,
        constraints = listOf(StringConstraints.Length(max = 50), nonDuplicateNameConstraint),
        onValidChange = ::updateSubmitBtn,
        onENTER = { modalComp.primaryButton.click() },
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

    fun setExistingFilenames(existingFilenames: List<String>): List<String> {
        disallowedInputs = existingFilenames.map { it.lowercase() }
        return disallowedInputs
    }

    private fun reinitialise() {
        filenameField.rebuild()
        filenameField.validateInitial()
    }

    private fun updateSubmitBtn(isValid: Boolean) {
        modalComp.primaryButton.setEnabled(isValid)
    }
}