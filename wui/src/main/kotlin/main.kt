import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import pages.AddCoursePage
import pages.CoursesPage
import pages.ExercisesPage
import pages.Navbar
import spa.PageManager
import spa.setupHistoryNavInterception
import spa.setupLinkInterception


private val PAGES = listOf(
        CoursesPage, ExercisesPage, AddCoursePage)


fun main() {
    val funLog = debugFunStart("main")

    // Start authentication as soon as possible
    MainScope().launch {
        initAuthentication()
        buildStatics()
        updateAccountData()
        // Register pages in async block to avoid race condition
        PageManager.registerPages(PAGES)
        PageManager.updatePage()
    }

    // Do stuff that does not require auth
    setupLinkInterception()
    setupHistoryNavInterception()

    funLog?.end()
}

fun buildStatics() {
    Navbar.build()
    // sidebar
}


private suspend fun updateAccountData() {
    val funLog = debugFunStart("updateAccountData")

    val firstName = Keycloak.firstName
    val lastName = Keycloak.lastName
    val email = Keycloak.email

    debug { "Updating account data to [email: $email, first name: $firstName, last name: $lastName]" }

    val personalData = mapOf("email" to email, "first_name" to firstName, "last_name" to lastName)

    val resp = fetchEms("/account/personal", ReqMethod.POST, personalData).await()
    if (resp.http200)
        debug { "Account data updated" }
    else
        warn { "Updating account data failed with status ${resp.status}" }

    funLog?.end()
}

private suspend fun initAuthentication() {
    val funLog = debugFunStart("initAuthentication")

    Keycloak.initialize().await()

    funLog?.end()
}
