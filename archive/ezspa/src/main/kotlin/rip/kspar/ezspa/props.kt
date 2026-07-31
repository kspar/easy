package rip.kspar.ezspa

import kotlinx.browser.document
import org.w3c.dom.NodeFilter
import rip.kspar.ezspa.EzSpa.Logger.warn
import kotlin.reflect.KProperty

class TextProp<T>(var value: T) {
    private val id = IdGenerator.nextId()

    // Explicit text after the starting comment is required to create a text node (in this case, a space).
    // Ending comment is required because otherwise our text node could get merged with a succeeding text node, and we'd overwrite that text
    override fun toString() = "<!--$id--> " + sanitiseHTML(value.toString()) + "<!---->"

    operator fun getValue(thisRef: Component, property: KProperty<*>): T = value

    operator fun setValue(thisRef: Component, property: KProperty<*>, newValue: T) {
        value = newValue

        val iterator = document.createNodeIterator(thisRef.rootElement, NodeFilter.SHOW_COMMENT) {
            if (it.textContent == id)
                NodeFilter.FILTER_ACCEPT
            else
                NodeFilter.FILTER_REJECT
        }

        // we allow several rendered props and update all of them
        generateSequence { iterator.nextNode() }.forEach { dstCommentNode ->
            val valueNode = dstCommentNode.nextSibling
            if (valueNode != null)
                valueNode.textContent = newValue.toString()
            else
                warn { "Value node is null for prop ${property.name}, destination comment node: $dstCommentNode, id: $id" }
        }
    }
}