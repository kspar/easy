package components.chips

import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator


typealias FilterChipId = String

sealed class FilterChipComponent : Component() {
    data class Filter(
        val label: String,
        val icon: String? = null,
        var selected: Boolean,
        val id: String = IdGenerator.nextId()
    )
}
