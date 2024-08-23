package components.chips

import rip.kspar.ezspa.Component
import template

class FilterChipSetComp(
    private val chips: List<FilterChipComponent>,
) : Component() {

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

    fun getActiveFilters(): Map<FilterChipId, FilterChipComponent.Filter?> =
        chips.associate {
            when (it) {
                is FilterToggleChipComp -> it.id to (if (it.filter.selected) it.filter else null)
                is FilterDropdownChipComp -> {
                    val selectedFilter = it.filters.singleOrNull { it.selected }
                    it.id to selectedFilter
                }
            }
        }
}