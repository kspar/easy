import rip.kspar.ezspa.Component
import rip.kspar.ezspa.getElemById

fun Component.hide(retainSpace: Boolean = false) {
    show(false, retainSpace)
}

fun Component.show(show: Boolean = true, retainSpace: Boolean = false) =
    getElemById(dstId).show(show, retainSpace)

