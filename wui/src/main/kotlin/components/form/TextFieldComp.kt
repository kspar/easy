package components.form

import components.form.validation.FieldConstraint
import components.form.validation.StringConstraints
import components.form.validation.ValidatableFieldComp
import libheaders.Materialize
import org.w3c.dom.Element
import org.w3c.dom.HTMLTextAreaElement
import rip.kspar.ezspa.*
import template


class TextFieldComp(
    private val label: String,
    private val isRequired: Boolean,
    private val placeholderHtml: String = "",
    var isDisabled: Boolean = false,
    private val paintRequiredOnCreate: Boolean = false,
    private val paintRequiredOnInput: Boolean = true,
    private val fieldNameForMessage: String = label,
    private val startActive: Boolean = false,
    private val initialValue: String = "",
    private val helpText: String = "",
    constraints: List<FieldConstraint<String>> = emptyList(),
    onValidChange: ((Boolean) -> Unit)? = null,
    private val onValueChange: (suspend (String) -> Unit)? = null,
    private val trimValue: Boolean = true,
    parent: Component
) : ValidatableFieldComp<String>(
    fieldNameForMessage,
    if (isRequired) StringConstraints.NotBlank(fieldNameForMessage.isNotBlank()) else null,
    constraints,
    onValidChange,
    parent
) {
    private val elementId = IdGenerator.nextId()

    override fun getValue(): String = getElement().value.let { if (trimValue) it.trim() else it }
    override fun getElement(): HTMLTextAreaElement = getElemByIdAs(elementId)
    override fun getHelperElement(): Element = getElemById("field-helper-$elementId")

    override val paintEmptyViolationInitial = paintRequiredOnCreate

    override fun render() = template(
        """
            <div class="input-field">
                <textarea id="{{id}}" class="materialize-textarea" {{#disabled}}disabled{{/disabled}} placeholder="{{{placeholder}}}">{{value}}</textarea>
                <label for="{{id}}" class="{{#active}}active{{/active}}">{{label}}</label>
                <span id="field-helper-{{id}}" class="helper-text {{#helpText}}has-text{{/helpText}}">{{helpText}}</span>
            </div>
        """.trimIndent(),
        "id" to elementId,
        "disabled" to isDisabled,
        "placeholder" to placeholderHtml,
        "value" to initialValue,
        "active" to (startActive || initialValue.isNotBlank()),
        "label" to label,
        "helpText" to helpText,
    )

    override fun postRender() {
        super.postRender()
        getElement().onInput {
            validateAndPaint(paintRequiredOnInput)
            onValueChange?.invoke(getValue())
        }
        // TODO: materialize bug: this will correctly resize here but the new height will remain the minimum,
        //  it won't shrink any smaller when input is deleted - check if new version fixes
        Materialize.textareaAutoResize(getElement())
    }

    override fun hasUnsavedChanges() = getValue() != initialValue
}