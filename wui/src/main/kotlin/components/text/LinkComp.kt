package components.text

import onSingleClickWithDisabled
import org.w3c.dom.HTMLAnchorElement
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.getElemByIdAs
import template


class LinkComp(
    private val label: String,
    private val icon: String? = null,
    private val loadingLabel: String? = null,
    private val onClick: () -> Unit,
    parent: Component?,
    dstId: String = IdGenerator.nextId()
) : Component(parent, dstId) {

    private val id: String = IdGenerator.nextId()

    override fun render(): String = template(
        """
            <ez-link><a id="{{id}}" href="#!">{{{iconHtml}}}{{label}}</a></ez-link>
        """.trimIndent(),
        "id" to id,
        "iconHtml" to icon,
        "label" to label
    )

    override fun postRender() {
        getElemByIdAs<HTMLAnchorElement>(id).onSingleClickWithDisabled(loadingLabel) {
            onClick()
        }
    }
}
