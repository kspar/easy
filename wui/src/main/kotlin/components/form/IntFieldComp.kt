package components.form

import components.form.validation.FieldConstraint
import components.form.validation.IntConstraints
import components.form.validation.StringConstraints
import components.form.validation.ValidatableFieldComp
import org.w3c.dom.Element
import org.w3c.dom.HTMLInputElement
import rip.kspar.ezspa.*
import tmRender


class IntFieldComp(
    private val label: String,
    private val isRequired: Boolean,
    private val minValue: Int = Int.MIN_VALUE,
    private val maxValue: Int = Int.MAX_VALUE,
    private val paintRequiredOnCreate: Boolean = false,
    private val paintRequiredOnInput: Boolean = true,
    private val fieldNameForMessage: String = label,
    private val initialValue: Int? = null,
    private val helpText: String = "",
    constraints: List<FieldConstraint<String>> = emptyList(),
    private val onValidChange: ((Boolean) -> Unit)? = null,
    private val onValueChange: ((Int?) -> Unit)? = null,
    parent: Component
) : ValidatableFieldComp<String>(
    fieldNameForMessage,
    if (isRequired) StringConstraints.NotBlank(fieldNameForMessage.isNotBlank()) else null,
    listOf(IntConstraints.MustBeInt(), IntConstraints.MinMax(minValue, maxValue)) + constraints,
    onValidChange,
    parent
) {
    private val inputId = IdGenerator.nextId()

    override fun getValue(): String = getElement().value
    override fun getElement(): HTMLInputElement = getElemByIdAs(inputId)
    override fun getHelperElement(): Element = getElemById("field-helper-$inputId")

    fun getIntValue(): Int? = getValue().toIntOrNull()

    override val paintEmptyViolationInitial = paintRequiredOnCreate

    override fun render() = tmRender(
        "t-c-int-field",
        "id" to inputId,
        "min" to minValue.toString(),
        "max" to maxValue.toString(),
        "value" to initialValue?.toString(),
        "label" to label,
        "helpText" to helpText,
    )

    override fun postRender() {
        super.postRender()
        getElement().onInput {
            validateAndPaint(paintRequiredOnInput)
            onValueChange?.invoke(getIntValue())
        }
    }

    override fun hasUnsavedChanges() = getIntValue() != initialValue
}