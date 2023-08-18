package pages.sidenav

import rip.kspar.ezspa.Component
import rip.kspar.ezspa.getElemById
import rip.kspar.ezspa.onVanillaClick
import template

class SidenavPageSectionComp(
    private val pageSection: Sidenav.PageSection,
    parent: Component,
    dstId: String,
) : Component(parent, dstId) {

    override fun render() = template(
        """
            <li><div class="divider"></div></li>
            <li title="{{pageSectionTitle}}"><a class="subheader truncate">{{pageSectionTitle}}</a></li>
            {{#pageItems}}
                <li><a class="waves-effect sidenav-close" id="{{id}}" {{#href}}href="{{href}}"{{/href}}>{{{icon}}}{{text}}</a></li>
            {{/pageItems}}
        """.trimIndent(),
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