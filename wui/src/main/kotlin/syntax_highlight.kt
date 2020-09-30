import libheaders.HighlightJS
import org.w3c.dom.NodeList
import org.w3c.dom.asList
import rip.kspar.ezspa.getNodelistBySelector


fun NodeList.highlightCode() {
    this.asList().forEach {
        HighlightJS.highlightBlock(it)
    }
}

fun highlightCode() {
    getNodelistBySelector("pre.highlightjs.highlight code.hljs").highlightCode()
}
