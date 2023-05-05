package libheaders

import org.w3c.dom.Element


inline fun Element.focus(): Unit = asDynamic().focus().unsafeCast<Unit>()

external fun isNaN(o: Any): Boolean

