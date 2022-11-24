package components.form


import components.form.validation.ConstraintViolation
import components.form.validation.FieldConstraint
import components.form.validation.ValidatableFieldComp
import org.w3c.dom.Element
import org.w3c.dom.HTMLInputElement
import rip.kspar.ezspa.*
import tmRender


class RadioButtonsComp(
    private val buttons: List<Button>,
    private val selectLineAfterButtons: Boolean = false,
    isRequired: Boolean = true,
    private val paintRequired: Boolean = false,
    fieldNameForMessage: String = "",
    private val onValueChange: ((Button?) -> Unit)? = null,
    onValidChange: ((Boolean) -> Unit)? = null,
    parent: Component
    // Boolean in the validation context signifies whether any option is selected; value is chosen -> true
) : ValidatableFieldComp<Boolean>(
    fieldNameForMessage,
    if (isRequired) RequiredConstraint() else null,
    emptyList(),
    onValidChange,
    parent
) {
    data class Button(
        val label: String,
        val id: String = IdGenerator.nextId(),
        val type: Type = Type.SELECTABLE
    )

    enum class Type { SELECTABLE, PRESELECTED, DISABLED }

    class RequiredConstraint : FieldConstraint<Boolean>() {
        override fun validate(value: Boolean, fieldNameForMessage: String): ConstraintViolation<Boolean>? {
            return if (!value) violation("$fieldNameForMessage valimine on kohustuslik") else null
        }
    }

    private val elementId = IdGenerator.nextId()
    private val groupId = IdGenerator.nextId()

    override fun getValue(): Boolean = getSelectedOption() != null
    override fun getElement(): Element = getElemById(elementId)
    override fun getHelperElement(): Element = getElemById("field-helper-$elementId")

    override val paintEmptyViolationInitial = paintRequired

    override fun render() = tmRender(
        "t-c-radio-buttons",
        "elementId" to elementId,
        "buttons" to buttons.map {
            mapOf(
                "id" to it.id,
                "groupId" to groupId,
                "hasLines" to selectLineAfterButtons,
                "label" to it.label,
                "isSelected" to (it.type == Type.PRESELECTED),
                "isDisabled" to (it.type == Type.DISABLED),
            )
        },
    )

    override fun postRender() {
        super.postRender()

        getElement().getElemsBySelector("input[name=$groupId]").forEach {
            it.addEventListener("click", {
                validateAndPaint(paintRequired)
                onValueChange?.invoke(getSelectedOption())
            })
        }
    }

    fun getSelectedOption(): Button? {
        val el = getElement().getElemBySelectorOrNull("input[name=$groupId]:checked") as? HTMLInputElement
        return if (el != null) {
            buttons.first { it.id == el.value }
        } else null
    }
}