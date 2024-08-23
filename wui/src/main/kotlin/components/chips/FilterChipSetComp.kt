package components.chips

import rip.kspar.ezspa.Component
import template

class FilterChipSetComp(
    chipProvider: (parent: Component) -> List<FilterDropdownChipComp>,
    parent: Component
) : Component(parent) {

    private val chips = chipProvider(this)

    override val children: List<Component>
        get() = chips

    override fun render() = template(
        """
            <md-chip-set>
                {{#chips}}
                    {{{dst}}}
                {{/chips}}
            </md-chip-set>
        """.trimIndent(),
        "chips" to chips.map {
            mapOf("dst" to it.toString())
        }
    )

    fun getActiveFilters(): Map<FilterChipId, FilterDropdownChipComp.Filter?> =
        chips.associate {
            val selectedFilter = it.filters.singleOrNull { it.selected }
            it.id to selectedFilter
        }

}