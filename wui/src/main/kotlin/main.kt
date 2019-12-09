import components.*
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import spa.PageManager
import spa.setupHistoryNavInterception
import spa.setupLinkInterception


private val PAGES = listOf(
        CoursesPage, ExercisesPage, ExerciseSummaryPage, AddCoursePage, ParticipantsPage, GradeTablePage,
        NewExercisePage)


fun main() {
    val funLog = debugFunStart("main")

    // Start authentication as soon as possible
    MainScope().launch {
        initAuthentication()
        updateAccountData()
        PageManager.registerPages(PAGES)
        buildStatics()
        PageManager.updatePage()
    }

    // Do stuff that does not require auth
    setupLinkInterception()
    setupHistoryNavInterception()

    funLog?.end()
}

fun buildStatics() {
    getHeader().innerHTML = """<div id="nav-wrap"></div>"""
    getMain().innerHTML = """<div id="sidenav-wrap"></div>
<div id="content-container" class="container"></div>"""
    Navbar.build()
}


private suspend fun updateAccountData() {
    val funLog = debugFunStart("updateAccountData")

    val firstName = Auth.firstName
    val lastName = Auth.lastName
    val email = Auth.email

    debug { "Updating account data to [email: $email, first name: $firstName, last name: $lastName]" }

    val personalData = mapOf("email" to email, "first_name" to firstName, "last_name" to lastName)

    val resp = fetchEms("/account/checkin", ReqMethod.POST, personalData).await()
    if (resp.http200)
        debug { "Account data updated" }
    else
        warn { "Updating account data failed with status ${resp.status}" }

    funLog?.end()
}

private suspend fun initAuthentication() {
    val funLog = debugFunStart("initAuthentication")

    Auth.initialize().await()

    funLog?.end()
}
