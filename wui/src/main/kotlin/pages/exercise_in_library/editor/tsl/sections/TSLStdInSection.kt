package pages.exercise_in_library.editor.tsl.sections

import Icons
import components.form.ButtonComp
import components.form.TextFieldComp
import kotlinx.coroutines.await
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import show
import template
import translation.Str

class TSLStdInSection(
    private var inputs: List<String>,
    private val onUpdate: () -> Unit,
    private val onValidChanged: () -> Unit,
    parent: Component,
) : Component(parent) {

    private var showField: Boolean = inputs.isNotEmpty()

    private var showBtn: ButtonComp? = null
    private var textField: TextFieldComp? = null

    override val children: List<Component>
        get() = listOfNotNull(showBtn, textField)

    override fun create() = doInPromise {
        if (showField) {
            textField = TextFieldComp(
                "", false,
                startActive = true,
                initialValue = inputs.joinToString("\n"),
                helpText = Str.tslStdinFieldHelp,
                // TODO: onUnfocus or debounce for performance
                onValueChange = { onUpdate() },
                onValidChange = { onValidChanged() },
                parent = this
            )
            showBtn = null
        } else {
            textField = null
            showBtn = ButtonComp(ButtonComp.Type.FLAT, Str.tslStdin, Icons.add, ::showSection, parent = this)
        }
    }

    override fun render() = template(
        """
            {{#showField}}
                <ez-flex style='align-items: baseline; color: var(--ez-text-inactive); margin-top: 1.5rem;'>
                    <ez-inline-flex style='align-self: center; margin-right: 1rem;'>${Icons.tslStdInput}</ez-inline-flex>
                    {{stdins}}
                </ez-flex>
                <ez-tsl-input-data-field id='${textField?.dstId}'></ez-tsl-input-data-field>
            {{/showField}}
            {{^showField}}
                <ez-tsl-add-button id='${showBtn?.dstId}'></ez-tsl-add-button>
            {{/showField}}
        """.trimIndent(),
        "showField" to showField,
        "stdins" to Str.tslStdins,
    )

    fun getInputs(): List<String> = textField?.getValue().orEmpty().split("\n").filter(String::isNotBlank)

    private suspend fun showSection() {
        showField = true
        createAndBuild().await()
    }

    fun setEditable(nowEditable: Boolean) {
        showBtn?.show(nowEditable)
        textField?.isDisabled = !nowEditable
        textField?.rebuild()
    }

    fun isValid() = textField?.isValid ?: true
}
