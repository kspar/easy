import pages.CoursesPage
import pages.ExercisesPage
import spa.PageManager
import spa.setupHistoryNavInterception
import spa.setupLinkInterception


fun main() {
    val funLog = debugFunStart("main")

    initAuthentication()

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
private fun renderOnce() {
    val funLog = debugFunStart("renderOnce")

    funLog?.end()
}

private fun initAuthentication() {
    val funLog = debugFunStart("initAuthentication")

    // TODO: race condition here: should not proceed with processing until auth has finished
    // await...
    Keycloak.init(objOf("onLoad" to "login-required"))
            .success { authenticated: Boolean ->
                debug { "Authenticated: $authenticated" }
            }
            .error { error ->
                debug { "Authentication error: $error" }
            }

    funLog?.end()
}
