package components.form


import org.w3c.dom.HTMLInputElement
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.getElemByIdAs
import rip.kspar.ezspa.onInput
import template


class CheckboxComp(
    private val label: String,
    private val initialValue: Boolean = false,
    var isDisabled: Boolean = false,
    private val onValueChange: ((Boolean) -> Unit)? = null,
    parent: Component
) : Component(parent) {

    private val inputId = IdGenerator.nextId()

    private fun getValue(): Boolean = getElement().checked
    private fun getElement(): HTMLInputElement = getElemByIdAs(inputId)

    override fun render() = template(
        """
            <ez-checkbox>
                <label for="{{id}}">
                    <input id="{{id}}" type="checkbox" {{#disabled}}disabled="disabled"{{/disabled}}>
                    <span>{{label}}</span>
                </label>
            </ez-checkbox>
        """.trimIndent(),
        "id" to inputId,
        "label" to label,
        "disabled" to isDisabled,
    )

    override fun postRender() {
        super.postRender()

        if (initialValue)
            getElement().checked = true

        getElement().onInput {
            onValueChange?.invoke(getValue())
        }
    }

    val isChecked
        get() = getValue()
}