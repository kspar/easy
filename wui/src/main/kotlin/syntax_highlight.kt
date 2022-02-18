import libheaders.HighlightJS
import rip.kspar.ezspa.getElemsBySelector


fun highlightCode() {
    getElemsBySelector("pre.highlightjs.highlight code.hljs").forEach {
        HighlightJS.highlightElement(it)
    }
}
