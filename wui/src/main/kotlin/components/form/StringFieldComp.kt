package components.form

import components.form.validation.FieldConstraint
import components.form.validation.StringConstraints
import components.form.validation.ValidatableFieldComp
import org.w3c.dom.Element
import org.w3c.dom.HTMLInputElement
import rip.kspar.ezspa.*
import tmRender


class StringFieldComp(
    private val label: String,
    private val isRequired: Boolean,
    private val paintRequiredOnCreate: Boolean = false,
    private val paintRequiredOnInput: Boolean = true,
    private val fieldNameForMessage: String = label,
    private val initialValue: String = "",
    private val helpText: String = "",
    constraints: List<FieldConstraint<String>> = emptyList(),
    private val onValidChange: ((Boolean) -> Unit)? = null,
    private val onValueChange: ((String) -> Unit)? = null,
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

    override fun render() = tmRender(
        "t-c-string-field",
        "id" to inputId,
        "value" to initialValue,
        "label" to label,
        "helpText" to helpText,
    )

    override fun postRender() {
        super.postRender()
        getElement().onInput {
            validateAndPaint(paintRequiredOnInput)
            onValueChange?.invoke(getValue())
        }
    }

    /**
     * Validate initial value of this field.
     * Call when ready to receive validChange events.
     * Will always fire an initial validChange event to ensure that valid state is in sync.
     */
    // TODO: consider moving to ValidatableFieldComp
    fun validateInitial() {
        validateAndPaint(paintRequiredOnCreate)
        onValidChange?.invoke(isValid)
    }

    fun focus() = getElement().focus()
}