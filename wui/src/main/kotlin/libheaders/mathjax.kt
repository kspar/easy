package libheaders

import kotlin.js.Promise

@JsName("MathJax")
external object MathJaxJS {
    fun typeset()
    fun typesetPromise(): Promise<Unit>
}

