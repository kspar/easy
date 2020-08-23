import kotlinx.coroutines.await
import libheaders.CodeMirror
import pages.*
import pages.course_exercises.CourseExercisesPage
import pages.courses.CoursesPage
import pages.exercise.ExercisePage
import queries.ReqMethod
import queries.abortAllFetchesAndClear
import queries.fetchEms
import queries.http200


private val PAGES = listOf(
        CoursesPage, CourseExercisesPage, ExerciseSummaryPage, ParticipantsPage, GradeTablePage,
        ExercisePage)


fun main() {
    val funLog = debugFunStart("main")

    // Start authentication as soon as possible
    doInPromise {
        initAuthentication()
        updateAccountData()
        buildStatics()
        EzSpa.PageManager.updatePage()
    }

    // Do stuff that does not require auth
    initApplication()
    EzSpa.Navigation.enableAnchorLinkInterception()
    EzSpa.Navigation.enableHistoryNavInterception()

    funLog?.end()
}

fun buildStatics() {
    getHeader().innerHTML = """<div id="nav-wrap"></div>"""
    getMain().innerHTML = """<div id="leftbar-wrap"></div>
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

    fetchEms("/account/checkin", ReqMethod.POST, personalData, successChecker = { http200 }, cancellable = false).await()
    debug { "Account data updated" }

    funLog?.end()
}

private suspend fun initAuthentication() {
    val funLog = debugFunStart("initAuthentication")
    Auth.initialize().await()
    funLog?.end()
}

private fun initApplication() {
    EzSpa.PageManager.registerPages(PAGES)
    EzSpa.PageManager.preUpdateHook = ::abortAllFetchesAndClear
    EzSpa.PageManager.pageNotFoundHandler = ::handlePageNotFound

    EzSpa.Logger.logPrefix = "[EZ-SPA] "
    EzSpa.Logger.debugFunction = ::debug
    EzSpa.Logger.warnFunction = ::warn

    CodeMirror.modeURL = AppProperties.CM_MODE_URL_TEMPLATE
}

private fun handlePageNotFound(@Suppress("UNUSED_PARAMETER") path: String) {
    getContainer().innerHTML = tmRender("tm-broken-page", mapOf(
            "title" to Str.notFoundPageTitle(),
            "msg" to Str.notFoundPageMsg()
    ))
}
