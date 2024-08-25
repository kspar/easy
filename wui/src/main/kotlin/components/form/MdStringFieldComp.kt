package components.form

import debug
import libheaders.MdTextField
import rip.kspar.ezspa.*
import template


class MdGradeFieldExperimentComp(
    private val label: String,
    private val placeholder: String? = null,
    private val initialValue: Int? = null,
    private val onValueChange: (suspend (Int) -> Unit)? = null,
    private val onENTER: (suspend (Int) -> Unit)? = null,
) : Component() {

    private val element
        get() = rootElement.getElemBySelector("md-outlined-text-field")

    private val mdField
        get() = rootElement.getElemBySelector("md-outlined-text-field").MdTextField()


    override fun render() = template(
        """
            <md-outlined-text-field
                type='number'
                min='0' max='100'
                no-spinner='true'
                label="{{label}}"
                {{#hasValue}}value="{{value}}"{{/hasValue}}
                placeholder="{{placeholder}}"
                suffix-text="/ 100">
            </md-outlined-text-field>
        """.trimIndent(),
        "hasValue" to (initialValue != null),
        "value" to initialValue,
        "label" to label,
        "placeholder" to placeholder,
    )

    override fun postRender() {
        element.onFocus {
            mdField.select()
        }
        element.onInput {
            onValueChange?.invoke(getValue())
        }
        element.onENTER {
            onENTER?.invoke(getValue())
        }
    }

    fun getValue() = mdField.valueAsNumber.toInt()

    override fun hasUnsavedChanges() = (getValue() != initialValue).also { debug { it } }

}