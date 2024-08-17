package components.code_editor

import components.form.StringFieldComp
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
            constraints = listOf(StringConstraints.Length(max = 100)),
            onValidChange = { modal.primaryButton.setEnabled(it) },
            onENTER = { modal.primaryButton.click() },
            parent = modal
        )

        modal.setContentComps { listOf(field) }
    }

    override fun postChildrenBuilt() {
        field.validateInitial()
    }

    suspend fun openAndWait(fileName: String): String? {
        this.fileName = fileName
        createAndBuild().await()
        return modal.openWithClosePromise().await()
    }
}