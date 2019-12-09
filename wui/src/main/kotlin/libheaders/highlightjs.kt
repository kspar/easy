package libheaders

import org.w3c.dom.Node
import org.w3c.dom.NodeList
import org.w3c.dom.asList

@JsName("hljs")
external object HighlightJS {
    fun highlightBlock(node: Node)
}

fun NodeList.highlightCode() {
    this.asList().forEach {
        HighlightJS.highlightBlock(it)
    }
}
