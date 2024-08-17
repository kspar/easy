package components

import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import template

class DropdownIconMenuComp(
    val icon: String,
    val label: String?,
    val items: List<DropdownMenuComp.Item>,
    parent: Component
) : Component(parent) {

    private val anchorId = IdGenerator.nextId()

    private val iconBtn = IconButtonComp(
        icon, label,
        stopPropagation = true,
        onClick = { dropdown.toggleOpen() },
        btnId = anchorId,
        parent = this
    )

    private val dropdown = DropdownMenuComp(anchorId, items, this)

    override val children: List<Component>
        get() = listOf(iconBtn, dropdown)

    override fun render() = template(
        """
            <ez-flex style='position: relative'>
                $iconBtn
                $dropdown
            </ez-flex>
        """.trimIndent(),
    )

    fun setEnabled(enabled: Boolean) {
        iconBtn.setEnabled(enabled)
    }
}