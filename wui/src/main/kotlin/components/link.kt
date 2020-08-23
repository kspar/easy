package components

import getElemByIdAs
import onSingleClickWithDisabled
import org.w3c.dom.HTMLAnchorElement
import Component
import IdGenerator
import tmRender


class LinkComp(
        private val label: String,
        private val icon: String?,
        private val loadingLabel: String? = null,
        private val onClick: () -> Unit,
        parent: Component?,
        dstId: String = IdGenerator.nextId()
) : Component(parent, dstId) {

    private val id: String = IdGenerator.nextId()

    override fun render(): String = tmRender("t-c-link",
            "id" to id,
            "icon" to icon,
            "label" to label
    )

    override fun postRender() {
        getElemByIdAs<HTMLAnchorElement>(id).onSingleClickWithDisabled(loadingLabel) {
            onClick()
        }
    }
}
