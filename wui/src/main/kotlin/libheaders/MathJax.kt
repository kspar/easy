package libheaders

import warn
import kotlin.js.Promise

@JsName("MathJax")
external object MathJaxJS {
    fun typeset()
    fun typesetPromise(): Promise<Unit>
}

object MathJax {
    fun typeset() {
        try {
            MathJaxJS.typeset()
        } catch (e: Throwable) {
            warn { "MathJax error: ${e.message}" }
        }
    }
}
