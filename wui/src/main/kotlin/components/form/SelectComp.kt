package components.form

import emptyToNull
import libheaders.Materialize
import org.w3c.dom.HTMLSelectElement
import rip.kspar.ezspa.*
import template

class SelectComp(
    private val label: String? = null,
    var options: List<Option>,
    var hasEmptyOption: Boolean = false,
    var isDisabled: Boolean = false,
    private val unconstrainedPosition: Boolean = false,
    private val onOptionChange: (suspend (String?) -> Unit)? = null,
    parent: Component
) : Component(parent) {

    data class Option(val label: String, val value: String, val preselected: Boolean = false)

    private val selectId = IdGenerator.nextId()
    private var initialValue: String? = null

    override fun render() = template(
        """
            <div class="input-field select-wrap">
            <select id="{{selectId}}" {{#isDisabled}}disabled{{/isDisabled}}>
                {{#hasEmptyOption}}
                    <option value="">–</option>
                {{/hasEmptyOption}}
                {{#options}}
                    <option value="{{value}}" {{#isSelected}}selected{{/isSelected}}>{{label}}</option>
                {{/options}}
            </select>
            {{#selectLabel}}
                <label>{{selectLabel}}</label>
            {{/selectLabel}}
        </div>
        """.trimIndent(),
        "selectId" to selectId,
        "selectLabel" to label,
        "isDisabled" to isDisabled,
        "hasEmptyOption" to hasEmptyOption,
        "options" to options.map {
            mapOf("value" to it.value, "isSelected" to it.preselected, "label" to it.label)
        }
    )

    override fun postRender() {
        Materialize.FormSelect.init(
            getElemById(selectId), objOf(
                "dropdownOptions" to objOf(
                    "coverTrigger" to false,
                    "autoFocus" to false,
                    "constrainWidth" to !unconstrainedPosition,
                    "container" to if (unconstrainedPosition) getBody() else null,
                ),
            )
        )

        val selectElement = getElemByIdAs<HTMLSelectElement>(selectId)
        selectElement.onChange {
            onOptionChange?.invoke(getValue())
        }

        initialValue = getValue()
    }

    fun getValue(): String? = getElemByIdAs<HTMLSelectElement>(selectId).value.emptyToNull()

    fun getLabelAndValue(): Pair<String?, String?> = getValue().let { value ->
        if (value == null)
            null to null
        else
            options.first { it.value == value }.label to value
    }

    override fun hasUnsavedChanges() = getValue() != initialValue
}
