package libheaders

import org.w3c.dom.Element

@JsName("hljs")
external object HighlightJS {
    fun highlightElement(element: Element)
}
