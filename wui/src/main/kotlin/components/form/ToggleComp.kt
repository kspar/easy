package components.form


import org.w3c.dom.HTMLInputElement
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.getElemByIdAs
import rip.kspar.ezspa.onInput
import template


class ToggleComp(
    private val offLabel: String,
    private val onLabel: String,
    private val initialValue: Boolean = false,
    var isDisabled: Boolean = false,
    private val onValueChange: (suspend (Boolean) -> Unit)? = null,
    parent: Component
) : Component(parent) {

    private val inputId = IdGenerator.nextId()

    private fun getValue(): Boolean = getElement().checked
    private fun getElement(): HTMLInputElement = getElemByIdAs(inputId)

    override fun render() = template(
        """
            <div class="switch">
              <label>
                {{off}}
                <input id='$inputId' type="checkbox" {{#disabled}}disabled{{/disabled}} {{#toggledOn}}checked{{/toggledOn}}>
                <span class="lever"></span>
                {{on}}
              </label>
            </div>
        """.trimIndent(),
        "off" to offLabel,
        "on" to onLabel,
        "disabled" to isDisabled,
        "toggledOn" to initialValue,
    )

    override fun postRender() {
        super.postRender()

        getElement().onInput {
            onValueChange?.invoke(getValue())
        }
    }

    val isToggled
        get() = getValue()
}