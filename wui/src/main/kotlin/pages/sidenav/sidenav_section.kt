package pages.sidenav

import kotlinx.dom.addClass
import kotlinx.dom.removeClass
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.getElemByIdOrNull

abstract class SidenavSectionComp(
    parent: Component,
    dstId: String,
) : Component(parent, dstId) {

    abstract fun getActivePageItemIds(): Map<ActivePage, String>

    open fun clearAndSetActivePage(activePage: ActivePage?) {
        clearActivePage()
        val activePageItemId = getActivePageItemIds()[activePage] ?: return
        paintItemActive(activePageItemId)
    }

    private fun clearActivePage() {
        getActivePageItemIds().values.forEach {
            getElemByIdOrNull(it)?.removeClass("active")
        }
    }

    protected fun paintItemActive(itemId: String) {
        // Item might be invisible for this role
        getElemByIdOrNull(itemId)?.addClass("active")
    }
}