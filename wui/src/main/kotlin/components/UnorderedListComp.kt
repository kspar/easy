package components

import rip.kspar.ezspa.Component
import tmRender

class UnorderedListComp(
    private val allItems: List<Item>,
    private val maxItemsToShow: Int = Int.MAX_VALUE,
    parent: Component
) : Component(parent) {

    data class Item(val title: String, val href: String? = null)

    private var shownItems = if (allItems.size <= maxItemsToShow) allItems else allItems.subList(0, maxItemsToShow - 1)

    private val expandLink = LinkComp(
        "Näita kõiki...", onClick = ::expand, parent = this
    )

    override val children: List<Component>
        get() = listOf(expandLink)

    override fun render() = tmRender(
        "t-c-unordered-list",
        "items" to shownItems.map {
            mapOf(
                "title" to it.title,
                "href" to it.href,
            )
        },
        "hasLink" to (shownItems.size != allItems.size),
        "expandLinkDst" to expandLink.dstId,
    )

    private fun expand() {
        shownItems = allItems
        rebuild()
    }
}