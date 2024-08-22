package components

import Icons
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import template

// Normal dropdown menu with a button trigger, similar to DropdownIconMenuComp
// TODO: delete if not used
class DropdownButtonMenuComp(
    val type: ButtonComp.Type,
    val label: String,
    val items: List<DropdownMenuComp.Item>,
    parent: Component
) : Component(parent) {

    private val anchorId = IdGenerator.nextId()

    val button = ButtonComp(
        type, label, Icons.dropdownBtnExpand,
        trailingIcon = true,
        onClick = { dropdown.toggleOpen() },
        btnId = anchorId,
        parent = this
    )

    private val dropdown = DropdownMenuComp(anchorId, items, this)

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
}