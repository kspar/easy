package pages

import spa.Component
import tmRender


data class Crumb(val label: String, val href: String? = null)

class BreadcrumbsComp(dstId: String,
                      private val crumbs: List<Crumb>
) : Component(dstId) {

    override fun render(): String = tmRender("t-c-breadcrumbs",
            "crumbs" to crumbs.map {
                mapOf("label" to it.label,
                        "href" to it.href)
            }
    )
}