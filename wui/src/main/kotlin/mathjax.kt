import libheaders.MathJaxJS
import org.w3c.dom.get
import kotlinx.browser.document
import kotlinx.browser.window

object MathJax {

    private const val CONTAINS_MATH_REGEX = "(?:\\\\\\(|\\\\\\[|\\\\begin\\{|\\$\\$)"

    fun formatPageIfNeeded(vararg newContent: String) {
        if (newContent.joinToString("").matches(CONTAINS_MATH_REGEX)) {
            if (window["MathJax"].unsafeCast<Boolean>()) {
                debug { "MathJax already loaded" }
                typeset()
            } else {
                // Load MathJax - will automatically typeset after load
                debug { "Loading MathJax" }
                val confScript = document.createElement("script")
                        .apply { textContent = AppProperties.MATHJAX_CONF }
                val script = document.createElement("script")
                        .apply { setAttribute("src", AppProperties.MATHJAX_SRC) }
                document.head?.apply {
                    appendChild(confScript)
                    appendChild(script)
                } ?: warn { "document.head is null" }
            }
        }
    }

    private fun typeset() {
        try {
            MathJaxJS.typeset()
        } catch (e: Throwable) {
            warn { "MathJax error: ${e.message}" }
        }
    }
}