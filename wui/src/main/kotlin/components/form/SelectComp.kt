package components.form

import libheaders.Materialize
import objOf
import org.w3c.dom.HTMLSelectElement
import rip.kspar.ezspa.*
import tmRender

class SelectComp(
        private val label: String,
        private val options: List<Option>,
        private val onOptionChange: ((String) -> Unit)? = null,
        parent: Component
) : Component(parent) {

    data class Option(val label: String, val value: String, val preselected: Boolean = false)

    private val selectId = IdGenerator.nextId()

    override fun render() = tmRender("t-c-select",
            "selectId" to selectId,
            "selectLabel" to label,
            "options" to options.map { mapOf("value" to it.value, "isSelected" to it.preselected, "label" to it.label) }
    )

    override fun postRender() {
        Materialize.FormSelect.init(getElemById(selectId), objOf(
                "dropdownOptions" to objOf(
                        "coverTrigger" to false,
                        "autoFocus" to false
                )
        ))
        val selectElement = getElemByIdAs<HTMLSelectElement>(selectId)
        selectElement.onChange {
            onOptionChange?.invoke(selectElement.value)
        }
    }
}
