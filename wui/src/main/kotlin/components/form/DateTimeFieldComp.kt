package components.form

import EzDate
import components.form.validation.DateTimeConstraints
import components.form.validation.FieldConstraint
import components.form.validation.ValidatableFieldComp
import libheaders.isNaN
import org.w3c.dom.Element
import org.w3c.dom.HTMLInputElement
import rip.kspar.ezspa.*
import template
import kotlin.js.Date


class DateTimeFieldComp(
    private val label: String,
    private val isRequired: Boolean,
    private val showRequiredMsg: Boolean = true,
    private val notInPast: Boolean = false,
    private val notInFuture: Boolean = false,
    private val paintRequiredOnCreate: Boolean = false,
    private val paintRequiredOnInput: Boolean = true,
    private val fieldNameForMessage: String = label,
    private val initialValue: EzDate? = null,
    private val helpText: String = "",
    private val htmlClasses: String = "",
    constraints: List<FieldConstraint<EzDate?>> = emptyList(),
    private val onValidChange: ((Boolean) -> Unit)? = null,
    private val onValueChange: ((EzDate?) -> Unit)? = null,
    parent: Component
) : ValidatableFieldComp<EzDate?>(
    fieldNameForMessage,
    if (isRequired) DateTimeConstraints.NonNullAndValid(showRequiredMsg) else null,
    buildList {
        if (notInPast) add(DateTimeConstraints.NotInPast)
        if (notInFuture) add(DateTimeConstraints.NotInFuture)
    } + constraints,
    onValidChange,
    parent
) {
    private val inputId = IdGenerator.nextId()

    // Check if date is valid (https://stackoverflow.com/a/38182068)
    override fun getValue(): EzDate? = Date(getElement().value).let { if (isNaN(it.getTime())) null else EzDate(it) }
    override fun getElement(): HTMLInputElement = getElemByIdAs(inputId)
    override fun getHelperElement(): Element = getElemById("field-helper-$inputId")

    override val paintEmptyViolationInitial = paintRequiredOnCreate

    override fun render() = template(
        """
            <ez-datetime-field class='{{class}}'>
                <div class="input-field" style='min-width: 10rem;'>
                    <input id="{{id}}" type="datetime-local" min="{{min}}" max="{{max}}" value="{{value}}">
                    <label for="{{id}}" class="active">{{label}}</label>
                    <span id="field-helper-{{id}}" class="helper-text {{#helpText}}has-text{{/helpText}}">{{helpText}}</span>
                </div>
            </ez-datetime-field>
        """.trimIndent(),
        "class" to htmlClasses,
        "id" to inputId,
        "min" to if (notInPast) EzDate.now().toDatetimeFieldString() else null,
        "max" to if (notInFuture) EzDate.now().toDatetimeFieldString() else null,
        "value" to initialValue?.toDatetimeFieldString(),
        "label" to label,
        "helpText" to helpText,
    )

    override fun postRender() {
        super.postRender()
        // onInput fires only when the field is completely filled, i.e. not for partial values
        getElement().onInput {
            validateAndPaint(paintRequiredOnInput)
            onValueChange?.invoke(getValue())
        }

        getElement().onBlur {
            validateAndPaint(paintRequiredOnInput)
        }
    }

    override fun hasUnsavedChanges() = getValue() != initialValue
}
