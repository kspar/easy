import pages.CoursesPage
import pages.ExercisesPage


fun main() {
    debug { "Yello" }

    PageManager.registerPages(CoursesPage, ExercisesPage)

    setupLinkInterception()
    setupHistoryNavInterception()

    renderOnce()
    PageManager.updatePage()
}

/**
 * Do actions that must be done only once per document load i.e. SPA refresh
 */
fun renderOnce() {
    debug { "renderOnce" }

}
