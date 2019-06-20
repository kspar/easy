import pages.CoursesPage
import pages.ExercisesPage
import spa.PageManager
import spa.setupHistoryNavInterception
import spa.setupLinkInterception


fun main() {
    val funLog = debugFunStart("main")

    // Start authentication as soon as possible
    initAuthentication { initAfterAuth() }

    // Do stuff that does not require auth immediately
    PageManager.registerPages(CoursesPage, ExercisesPage)
    setupLinkInterception()
    setupHistoryNavInterception()

    funLog?.end()
}

/**
 * Do actions that require authentication to be successful
 */
fun initAfterAuth() {
    val funLog = debugFunStart("initAfterAuth")

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

private fun initAuthentication(afterAuthCallback: () -> Unit) {
    val funLog = debugFunStart("initAuthentication")

    Keycloak.init(objOf("onLoad" to "login-required"))
            .success { authenticated: Boolean ->
                debug { "Authenticated: $authenticated" }
                afterAuthCallback()
            }
            .error { error ->
                debug { "Authentication error: $error" }
            }

    funLog?.end()
}
