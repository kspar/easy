package components

import Icons
import components.dropdown.DropdownButtonMenuSelectComp
import components.form.ButtonComp
import components.form.IconButtonComp
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.doInPromise
import template

class SorterComp(
    private val sorters: List<Sorter>,
    private val onChanged: suspend (activeSorter: Sorter, reversed: Boolean) -> Unit,
    initialSorter: Sorter = sorters.first(),
    initialReversed: Boolean = false,
) : Component() {

    data class Sorter(
        val label: String,
        val id: String = IdGenerator.nextId()
    )

    private lateinit var orderBtn: IconButtonComp
    private lateinit var select: DropdownButtonMenuSelectComp

    var activeSorter: Sorter = initialSorter
        private set

    var isReversed: Boolean = initialReversed
        private set

    override val children
        get() = listOf(orderBtn, select)

    override fun create() = doInPromise {

        orderBtn = IconButtonComp(
            Icons.arrowUp, null,
            toggle = IconButtonComp.Toggle(
                Icons.arrowDown,
                startToggled = isReversed,
            ),
            size = IconButtonComp.Size.SMALL,
            onClick = {
                isReversed = orderBtn.toggled
                onChanged(activeSorter, isReversed)
            },
            parent = this
        )

        select = DropdownButtonMenuSelectComp(
            ButtonComp.Type.TEXT,
            sorters.map {
                DropdownButtonMenuSelectComp.Item(it.label, selected = it.id == activeSorter.id, id = it.id)
            },
            onItemSelected = { item ->
                isReversed = false
                orderBtn.toggled = false
                activeSorter = sorters.first { it.id == item.id }
                onChanged(activeSorter, isReversed)
            },
            parent = this
        )
    }

    override fun render() = template(
        """
            <ez-flex>
                $orderBtn
                $select
            </ez-flex>
        """.trimIndent(),
    )
}