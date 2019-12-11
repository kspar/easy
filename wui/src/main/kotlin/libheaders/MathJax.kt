package libheaders

import kotlin.js.Promise

@JsName("MathJax")
external object MathJax {
    fun typeset()
    fun typesetPromise(): Promise<Unit>
}
