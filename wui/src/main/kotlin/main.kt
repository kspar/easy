import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import libheaders.M
import pages.CoursesPage
import pages.ExercisesPage
import spa.PageManager
import spa.setupHistoryNavInterception
import spa.setupLinkInterception
import kotlin.browser.document


private val PAGES = listOf(
        CoursesPage, ExercisesPage)


fun main() {
    val funLog = debugFunStart("main")

    // Start authentication as soon as possible
    MainScope().launch {
        initAuthentication()
        renderNavbar()
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


private fun renderNavbar() {
    val navHtml = tmRender("tm-navbar",
            mapOf("userName" to Keycloak.firstName,
                    "myCourses" to Str.myCourses,
                    "account" to Str.accountData,
                    "logOut" to Str.logOut,
                    "accountLink" to Keycloak.createAccountUrl(),
                    "logoutLink" to Keycloak.createLogoutUrl()))
    debug { "Navbar html: $navHtml" }
    getElemById("nav-wrap").innerHTML = navHtml

    val dropdownTrigger = getElemById("profile-wrapper")
    M.Dropdown.init(dropdownTrigger,
            mapOf("constrainWidth" to false,
                    "coverTrigger" to false,
                    "container" to document.body!!).toJsObj())
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
