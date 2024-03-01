package components

import Icons
import rip.kspar.ezspa.*
import template

typealias FilterChipId = String

class FilterDropdownChipComp(
    private val label: String,
    var filters: List<Filter>,
    var enabled: Boolean = true,
    val onChange: suspend (Filter?) -> Unit,
    val id: FilterChipId = IdGenerator.nextId(),
    parent: Component
) : Component(parent) {

    data class Filter(
        val label: String,
        val icon: String? = null,
        var selected: Boolean,
        val id: String = IdGenerator.nextId()
    )

    private val elementId = IdGenerator.nextId()

    private lateinit var dropdown: DropdownMenuComp

    override val children: List<Component>
        get() = listOf(dropdown)

    override fun create() = doInPromise {
        dropdown = DropdownMenuComp(
            elementId,
            filters.map {
                DropdownMenuComp.Item(it.label, it.icon, onSelected = ::selectFilter, id = it.id)
            },
            this
        )
    }

    override fun render() = template(
        """
            <span style='position: relative'>
                <md-filter-chip id='{{id}}' label="{{label}}" {{^enabled}}disabled{{/enabled}}>
                </md-filter-chip>
                $dropdown
            </span>
        """.trimIndent(),
        "id" to elementId,
        "label" to label,
        "enabled" to enabled,
        "activeIcon" to Icons.check,
    )

    override fun postRender() {
        getElemById(elementId).onVanillaClick(true) {
            dropdown.toggleOpen()
        }

        getElemById(elementId).addEventListener("remove", {
            it.preventDefault()
            setFilterSelected(null)
            changeLabel(label)
            setActive(false)
            dropdown.setSelected(null)
            doInPromise {
                onChange(null)
            }
        })
    }

    override fun postChildrenBuilt() {
        // select initial filter
        filters.singleOrNull { it.selected }?.let { changeFilterSelected(it) }
    }

    private suspend fun selectFilter(menuItem: DropdownMenuComp.Item) {
        val selectedFilter = filters.first { it.id == menuItem.id }
        changeFilterSelected(selectedFilter)
        onChange(selectedFilter)
    }

    private fun changeFilterSelected(filter: Filter) {
        setFilterSelected(filter)
        changeLabel(filter.label)
        setActive(true)
        dropdown.setSelected(filter.id)
    }

    private fun changeLabel(newLabel: String) {
        getElemById(elementId).setAttribute("label", newLabel)
    }

    private fun setActive(active: Boolean) {
        getElemById(elementId).let {
            if (active) {
                it.setAttribute("selected", "")
                it.setAttribute("removable", "")
                it.innerHTML = """<md-icon style='display: none;' slot="icon">${Icons.check}</md-icon>"""
            } else {
                it.removeAttribute("selected")
                it.removeAttribute("removable")
                it.innerHTML = ""
                dropdown.setOpen(false)
            }
        }
    }

    private fun setFilterSelected(filter: Filter?) {
        filters.forEach {
            it.selected = (it == filter)
        }
    }
}