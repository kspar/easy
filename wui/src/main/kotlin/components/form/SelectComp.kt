package components.form

import emptyToNull
import libheaders.Materialize
import org.w3c.dom.HTMLSelectElement
import rip.kspar.ezspa.*
import tmRender

class SelectComp(
    private val label: String? = null,
    var options: List<Option>,
    var hasEmptyOption: Boolean = false,
    private val onOptionChange: (suspend (String?) -> Unit)? = null,
    parent: Component
) : Component(parent) {

    data class Option(val label: String, val value: String, val preselected: Boolean = false)

    private val selectId = IdGenerator.nextId()
    private var initialValue: String? = null

    override fun render() = tmRender("t-c-select",
        "selectId" to selectId,
        "selectLabel" to label,
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
                    "autoFocus" to false
                )
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
