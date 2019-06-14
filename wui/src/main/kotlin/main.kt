import kotlin.browser.window

object Page {
    var id: String = ""
}

fun main() {
    println("wut")

    // Check path, update Page
    debug { window.location.pathname }

    // Render correct page contents


}

