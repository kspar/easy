import kotlinx.serialization.Serializable
import kotlinx.browser.window

@Serializable
data class ScrollPosition(val x: Double, val y: Double)

fun getWindowScrollPosition() = ScrollPosition(window.scrollX, window.scrollY)

fun restoreWindowScroll(position: ScrollPosition) {
    window.scroll(position.x, position.y)
}
