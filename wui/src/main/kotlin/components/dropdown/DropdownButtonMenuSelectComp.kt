package components.dropdown

import Icons
import components.form.ButtonComp
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import template

class DropdownButtonMenuSelectComp(
    val type: ButtonComp.Type,
    // items must be non-empty
    val items: List<Item>,
    val onItemSelected: suspend (Item) -> Unit,
    parent: Component
) : Component(parent) {

    data class Item(
        val label: String,
        var selected: Boolean = false,
        val id: String = IdGenerator.nextId()
    )

    private val anchorId = IdGenerator.nextId()

    val button = ButtonComp(
        type, "", Icons.dropdownBtnExpand,
        trailingIcon = true,
        onClick = { dropdown.toggleOpen() },
        btnId = anchorId,
        parent = this
    )

    private val dropdown = DropdownMenuComp(
        anchorId,
        items.map {
            DropdownMenuComp.Item(
                it.label,
                onSelected = ::selectItem,
                id = it.id
            )
        },
        this
    )

    override val children: List<Component>
        get() = listOf(button, dropdown)

    override fun render() = template(
        """
            <ez-flex style='position: relative'>
                $button
                $dropdown
            </ez-flex>
        """.trimIndent(),
    )

    override fun postChildrenBuilt() {
        // select initial item
        val selectedItem = items.singleOrNull { it.selected } ?: items.first()
        changeItemSelected(selectedItem)
    }

    private suspend fun selectItem(menuItem: DropdownMenuComp.Item) {
        val selectedItem = items.single { it.id == menuItem.id }
        changeItemSelected(selectedItem)
        onItemSelected(selectedItem)
    }

    private fun changeItemSelected(item: Item) {
        // change state
        items.forEach {
            it.selected = (it == item)
        }

        // change btn label
        button.label = item.label
        button.rebuild()

        // change dropdown active item
        dropdown.setSelected(item.id)
    }

}