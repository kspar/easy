package components.code_editor.parts

import components.form.StringFieldComp
import components.form.validation.ConstraintViolation
import components.form.validation.FieldConstraint
import components.form.validation.StringConstraints
import components.modal.BinaryModalComp
import kotlinx.coroutines.await
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import translation.Str

class CodeEditorRenameTabModal(
    private var fileName: String = "",
    parent: Component,
) : Component(parent) {

    private lateinit var modal: BinaryModalComp<String?>
    private lateinit var field: StringFieldComp

    private var bannedNames: List<String> = emptyList()

    private val noBannedInputsConstraint: FieldConstraint<String> = object : FieldConstraint<String>() {
        override fun validate(value: String, fieldNameForMessage: String): ConstraintViolation<String>? {
            return when {
                bannedNames.contains(value.lowercase()) -> violation(Str.existingFileNameError)
                else -> null
            }
        }
    }

    override val children: List<Component>
        get() = listOf(modal)

    override fun create() = doInPromise {

        modal = BinaryModalComp(
            "", Str.doSave, Str.cancel, Str.saving,
            defaultReturnValue = null,
            primaryAction = { field.getValue() },
            onOpened = { field.focus() },
            parent = this
        )

        field = StringFieldComp(
            Str.filename,
            true, paintRequiredOnInput = false,
            initialValue = fileName,
            constraints = listOf(StringConstraints.Length(max = 100), noBannedInputsConstraint),
            onValidChange = { modal.primaryButton.setEnabled(it) },
            onENTER = { modal.primaryButton.click() },
            parent = modal
        )

        modal.setContentComps { listOf(field) }
    }

    override fun postChildrenBuilt() {
        field.validateInitial()
    }

    suspend fun openAndWait(fileName: String, bannedFilenames: List<String>): String? {
        this.fileName = fileName
        this.bannedNames = bannedFilenames
        createAndBuild().await()
        return modal.openWithClosePromise().await()
    }
}