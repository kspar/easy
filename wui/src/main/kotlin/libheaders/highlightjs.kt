package libheaders

import org.w3c.dom.Node

@JsName("hljs")
external object HighlightJS {
    fun highlightBlock(node: Node)
}
