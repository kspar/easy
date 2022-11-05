import kotlinx.dom.addClass
import kotlinx.dom.removeClass
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.getElemById

fun Component.hide() {
    getElemById(dstId).addClass("display-none")
}

fun Component.show(show: Boolean = true) {
    if (show)
        getElemById(dstId).removeClass("display-none")
    else
        hide()
}
