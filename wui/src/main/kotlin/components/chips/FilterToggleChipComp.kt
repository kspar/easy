package components.chips

import libheaders.MdFilterChip
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.getElemById
import rip.kspar.ezspa.onVanillaClick
import template

class FilterToggleChipComp(
    val filter: Filter,
    var enabled: Boolean = true,
    val onChange: suspend (active: Boolean) -> Unit,
    val id: FilterChipId = IdGenerator.nextId(),
) : FilterChipComponent() {

    private val elementId = IdGenerator.nextId()

    override fun render() = template(
        """
            <md-filter-chip id='{{id}}' {{#selected}}selected{{/selected}} {{^enabled}}disabled{{/enabled}}>
                {{label}}
                {{#icon}}<md-icon slot="icon">{{{icon}}}</md-icon>{{/icon}}
            </md-filter-chip>
        """.trimIndent(),
        "id" to elementId,
        "label" to filter.label,
        "selected" to filter.selected,
        "enabled" to enabled,
        "icon" to filter.icon,
    )

    override fun postRender() {
        getElemById(elementId).onVanillaClick(false) {
            val instance = getElemById(elementId).MdFilterChip()
            val nowActive = instance.selected
            filter.selected = nowActive
            onChange(nowActive)
        }
    }
}