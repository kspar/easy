import rip.kspar.ezspa.Component
import rip.kspar.ezspa.getElemById

fun Component.hide() {
    show(false)
}

fun Component.show(show: Boolean = true) {
    if (show)
        getElemById(dstId).show()
    else
        getElemById(dstId).hide()
}
