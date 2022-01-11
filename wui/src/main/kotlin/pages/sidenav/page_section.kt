package pages.sidenav

import rip.kspar.ezspa.Component
import rip.kspar.ezspa.getElemById
import rip.kspar.ezspa.onVanillaClick
import tmRender

class SidenavPageSectionComp(
    private val pageSection: Sidenav.PageSection,
    parent: Component,
    dstId: String,
) : Component(parent, dstId) {

    override fun render() = tmRender(
        "t-c-sidenav-page-section",
        "pageSectionTitle" to pageSection.title,
        "pageItems" to pageSection.items.map {
            mapOf(
                "id" to it.id,
                "icon" to it.iconHtml,
                "text" to it.text,
                "href" to (if (it is Sidenav.Link) it.href else null),
            )
        }
    )

    override fun postRender() {
        pageSection.items.filterIsInstance<Sidenav.Action>().forEach { action ->
            getElemById(action.id).onVanillaClick(true) {
                action.onActivate(action)
            }
        }
    }
}