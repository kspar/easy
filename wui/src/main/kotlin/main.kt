import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import libheaders.CodeMirror
import pages.*
import pages.course_exercises.CourseExercisesPage
import pages.courses.CoursesPage
import pages.exercise.ExercisePage
import queries.ReqMethod
import queries.fetchEms
import queries.http200
import spa.PageManager
import spa.setupHistoryNavInterception
import spa.setupLinkInterception


private val PAGES = listOf(
        CoursesPage, CourseExercisesPage, ExerciseSummaryPage, AddCoursePage, ParticipantsPage, GradeTablePage,
        NewExercisePage, ExercisePage)


fun main() {
    val funLog = debugFunStart("main")

    // Start authentication as soon as possible
    MainScope().launch {
        initAuthentication()
        updateAccountData()
        PageManager.registerPages(PAGES)
        buildStatics()
        initApplication()
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

    val personalData = mapOf("first_name" to firstName, "last_name" to lastName)

    fetchEms("/account/checkin", ReqMethod.POST, personalData, successChecker = { http200 }).await()
    debug { "Account data updated" }

    funLog?.end()
}

private suspend fun initAuthentication() {
    val funLog = debugFunStart("initAuthentication")

    Auth.initialize().await()

    funLog?.end()
}

private fun initApplication() {
    CodeMirror.modeURL = AppProperties.CM_MODE_URL_TEMPLATE
}
