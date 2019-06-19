import pages.CoursesPage
import pages.ExercisesPage
import spa.PageManager
import spa.setupHistoryNavInterception
import spa.setupLinkInterception

@JsName("Keycloak")
external class Keycloak {
    val token: String

    var onTokenExpired: dynamic
    var onAuthRefreshSuccess: dynamic

    fun init(options: dynamic): dynamic
    fun updateToken(minValidSec: Int): dynamic
}


fun main() {
    val funLog = debugFunStart("main")

    PageManager.registerPages(CoursesPage, ExercisesPage)

    setupLinkInterception()
    setupHistoryNavInterception()

    renderOnce()
    PageManager.updatePage()

    funLog?.end()
}

/**
 * Do actions that must be done only once per document load i.e. SPA refresh
 */
fun renderOnce() {
    val funLog = debugFunStart("renderOnce")

    funLog?.end()
}
