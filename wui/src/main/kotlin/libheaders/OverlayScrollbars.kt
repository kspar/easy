package libheaders

import org.w3c.dom.Element

@JsModule("overlayscrollbars")
@JsNonModule
external object OverlayScrollbars {
    fun OverlayScrollbars(element: Element, options: dynamic)
}
