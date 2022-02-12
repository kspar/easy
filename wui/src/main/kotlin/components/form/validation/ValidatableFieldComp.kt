package components.form.validation

import kotlinx.dom.addClass
import kotlinx.dom.removeClass
import org.w3c.dom.Element
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.doInPromise

abstract class ValidatableFieldComp<ValueType>(
    private val fieldNameForMessage: String,
    private val nonEmptyConstraint: FieldConstraint<ValueType>? = null,
    private val otherConstraints: List<FieldConstraint<ValueType>> = emptyList(),
    private val onValidChange: ((Boolean) -> Unit)? = null,
    parent: Component,
    dstId: String = IdGenerator.nextId(),
) : Component(parent, dstId) {

    abstract fun getValue(): ValueType
    abstract fun getElement(): Element
    abstract fun getHelperElement(): Element

    // Valid by default
    private var currentViolation: ConstraintViolation<ValueType>? = null

    // Whether the current violation is painted (nonempty violations aren't always painted)
    private var violationIsPainted: Boolean = false

    override fun create() = doInPromise {
        currentViolation = null
        violationIsPainted = false
    }

    /**
     * Validate this field according to all constraints. Render the resulting violation if it changed.
     * This method can be called manually when the containing component has finished rendering and wishes
     * to update its state based on whether this field is (initially) valid. The validity change is communicated
     * through the [onValidChange] hook.
     *
     * Subclasses should call this method whenever their content changes and should be validated.
     *
     * @param paintEmptyViolation - if false, don't paint violations for the non-empty constraint, useful for
     * validating when first rendered since we don't want to show "is mandatory" messages at that time, or at
     * any time when the field is required to be nonempty, but we don't want to show errors for it.
     */
    fun validateAndPaint(paintEmptyViolation: Boolean = true) {
        val violationChanged = validate()

        // This can be false if this method was called with paintNonEmptyViolation=false before
        // and a nonempty violation was activated (but not painted)
        val violationIsCorrectlyPainted = violationIsPainted == (currentViolation != null)

        if (violationChanged || !violationIsCorrectlyPainted) {
            val violation = currentViolation
            if (violation == null || paintEmptyViolation || violation.constraint != nonEmptyConstraint) {
                paintViolation(violation, getElement(), getHelperElement())
            } else {
                // Unpaint violation if any, now "violationIsCorrectlyPainted" is false
                if (violationIsPainted)
                    paintViolation(null, getElement(), getHelperElement())
            }
        }
    }

    /**
     * @return whether the current violation changed
     */
    private fun validate(): Boolean {
        val value = getValue()
        val violation = getNonemptyViolation(value) ?: getOtherViolation(value)
        return if (currentViolation != violation) {
            currentViolation = violation
            onValidChange?.invoke(currentViolation == null)
            true
        } else {
            false
        }
    }

    private fun getNonemptyViolation(value: ValueType): ConstraintViolation<ValueType>? {
        return nonEmptyConstraint?.validate(value, fieldNameForMessage)
    }

    private fun getOtherViolation(value: ValueType): ConstraintViolation<ValueType>? {
        return otherConstraints.firstNotNullOfOrNull {
            it.validate(value, fieldNameForMessage)
        }
    }

    private fun paintViolation(violation: ConstraintViolation<ValueType>?, element: Element, helperElement: Element) {
        if (violation != null) {
            violationIsPainted = true
            element.addClass("invalid")
            helperElement.setAttribute("data-error", violation.message)
        } else {
            violationIsPainted = false
            element.removeClass("invalid")
            helperElement.removeAttribute("data-error")
        }
    }
}