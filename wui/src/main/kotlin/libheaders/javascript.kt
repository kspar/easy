package libheaders

import org.w3c.dom.Element


inline fun Element.focus(): Unit = asDynamic().focus().unsafeCast<Unit>()

external fun isNaN(o: Any): Boolean


external class ResizeObserver(callback: ((Array<ResizeObserverEntry>, observer: ResizeObserver) -> Unit)) {
    fun observe(element: Element)
}

external class ResizeObserverEntry {
    val borderBoxSize: Array<ResizeBorderBoxEntry>
}

external class ResizeBorderBoxEntry {
    // height
    val blockSize: Double
}

external class TypeError : Throwable