package pages.exercise.editor.tsl.sections

import Icons
import components.form.ButtonComp
import components.form.TextFieldComp
import hide
import rip.kspar.ezspa.Component
import show

class TSLStdInSection(
    private var inputs: List<String>,
    private val onUpdate: () -> Unit,
    private val onValidChanged: () -> Unit,
    parent: Component,
) : Component(parent) {

    private val showBtn =
        ButtonComp(ButtonComp.Type.FLAT, "Lisa kasutaja sisend", Icons.add, ::showSection, parent = this)

    private val textField = TextFieldComp(
        "Kasutaja sisendid", false, "42 &#x0a;andmed.txt",
        startActive = true,
        initialValue = inputs.joinToString("\n"),
        helpText = "Õpilase programmile antavad kasutaja sisendid, iga sisend eraldi real",
        // TODO: onUnfocus or debounce for performance
        onValueChange = { onUpdate() },
        onValidChange = { onValidChanged() },
        parent = this
    )

    override val children: List<Component>
        get() = listOf(showBtn, textField)

    override fun postRender() {
        if (inputs.isEmpty()) {
            textField.hide()
        } else {
            showBtn.hide()
        }
    }

    override fun postChildrenBuilt() {
        textField.validateInitial()
    }

    fun getInputs(): List<String> = textField.getValue().split("\n").filter { it.isNotBlank() }

    private suspend fun showSection() {
        showBtn.hide()
        textField.show()
    }

    fun setEditable(nowEditable: Boolean) {
        showBtn.setEnabled(nowEditable)
        textField.isDisabled = !nowEditable
        textField.rebuild()
    }

    fun isValid() = textField.isValid
}
