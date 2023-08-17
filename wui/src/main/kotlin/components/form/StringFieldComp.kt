package components.form

import components.form.validation.FieldConstraint
import components.form.validation.StringConstraints
import components.form.validation.ValidatableFieldComp
import org.w3c.dom.Element
import org.w3c.dom.HTMLInputElement
import rip.kspar.ezspa.*
import template


class StringFieldComp(
    private val label: String,
    private val isRequired: Boolean,
    private val placeholder: String? = null,
    var isDisabled: Boolean = false,
    private val paintRequiredOnCreate: Boolean = false,
    private val paintRequiredOnInput: Boolean = true,
    private val fieldNameForMessage: String = label,
    private val initialValue: String = "",
    private val helpText: String = "",
    constraints: List<FieldConstraint<String>> = emptyList(),
    private val onValidChange: ((Boolean) -> Unit)? = null,
    private val onValueChange: ((String) -> Unit)? = null,
    private val onENTER: (suspend (String) -> Unit)? = null,
    private val trimValue: Boolean = true,
    parent: Component
) : ValidatableFieldComp<String>(
    fieldNameForMessage,
    if (isRequired) StringConstraints.NotBlank() else null,
    constraints,
    onValidChange,
    parent
) {
    private val inputId = IdGenerator.nextId()

    override fun getValue(): String = getElement().value.let { if (trimValue) it.trim() else it }
    override fun getElement(): HTMLInputElement = getElemByIdAs(inputId)
    override fun getHelperElement(): Element = getElemById("field-helper-$inputId")

    override val paintEmptyViolationInitial = paintRequiredOnCreate

    override fun render() = template(
        """
            <div class="input-field">
                <input id="{{id}}" type="text" value="{{value}}" {{#disabled}}disabled{{/disabled}} {{#placeholder}}placeholder="{{placeholder}}"{{/placeholder}}>
                <label for="{{id}}" class="{{#value}}active{{/value}}">{{label}}</label>
                <span id="field-helper-{{id}}" class="helper-text {{#helpText}}has-text{{/helpText}}">{{helpText}}</span>
            </div>
        """.trimIndent(),
        "id" to inputId,
        "disabled" to isDisabled,
        "value" to initialValue,
        "label" to label,
        "placeholder" to placeholder,
        "helpText" to helpText,
    )

    override fun postRender() {
        super.postRender()
        getElement().onInput {
            validateAndPaint(paintRequiredOnInput)
            onValueChange?.invoke(getValue())
        }
        if (onENTER != null) {
            getElement().onENTER {
                onENTER.invoke(getValue())
            }
        }
    }

    override fun hasUnsavedChanges() = getValue() != initialValue

    fun setValue(value: String) {
        getElement().value = value
    }
}