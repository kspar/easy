import pages.CoursesPage
import pages.ExercisesPage


fun main() {
    val fl = debugFunStart("main")

    PageManager.registerPages(CoursesPage, ExercisesPage)

    setupLinkInterception()
    setupHistoryNavInterception()

    renderOnce()
    PageManager.updatePage()

    debugFunEnd(fl)
}

/**
 * Do actions that must be done only once per document load i.e. SPA refresh
 */
fun renderOnce() {
    val fl = debugFunStart("renderOnce")

    debugFunEnd(fl)
}
