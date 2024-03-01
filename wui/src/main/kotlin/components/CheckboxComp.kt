package components

import libheaders.MdCheckbox
import rip.kspar.ezspa.*
import template

class CheckboxComp(
    label: String? = null,
    value: Value = Value.UNCHECKED,
    enabled: Boolean = true,
    private val onChange: (suspend (Value) -> Unit)? = null,
    private val elementId: String = IdGenerator.nextId(),
    parent: Component
) : Component(parent) {

    enum class Value { CHECKED, INDETERMINATE, UNCHECKED }

    private val element
        get() = getElemById(elementId)

    private val instance
        get() = element.MdCheckbox()

    var label = label
        set(value) {
            field = value
            getElemBySelector("label[for='$elementId']").textContent = value
        }

    var value: Value = value
        set(value) {
            field = value
            instance.let {
                when (value) {
                    Value.CHECKED -> {
                        it.checked = true
                        it.indeterminate = false
                    }

                    Value.INDETERMINATE -> {
                        it.checked = false
                        it.indeterminate = true
                    }

                    Value.UNCHECKED -> {
                        it.checked = false
                        it.indeterminate = false
                    }
                }
            }
        }

    var enabled = enabled
        set(value) {
            field = value
            instance.disabled = !value
        }

    override fun render() = template(
        """
            <md-checkbox touch-target="wrapper" id="{{id}}"></md-checkbox>
            <label for='{{id}}'></label>
        """.trimIndent(),
        "id" to elementId,
    )

    override fun postRender() {
        value = value
        enabled = enabled
        label = label

        element.onChange {
            value = when {
                instance.indeterminate -> Value.INDETERMINATE
                instance.checked -> Value.CHECKED
                else -> Value.UNCHECKED
            }

            onChange?.invoke(value)
        }
    }
}