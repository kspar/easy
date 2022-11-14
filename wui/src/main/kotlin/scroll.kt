import kotlinx.browser.window
import kotlinx.serialization.Serializable

@Serializable
data class ScrollPosition(val x: Double, val y: Double)

fun getWindowScrollPosition() = ScrollPosition(window.scrollX, window.scrollY)

fun ScrollPosition.restore() {
    window.scroll(x, y)
}
