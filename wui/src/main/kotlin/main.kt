import kotlinx.browser.document
import kotlinx.coroutines.await
import kotlinx.dom.clear
import libheaders.CodeMirror
import libheaders.ContainerQueryPolyfill
import libheaders.OverlayScrollbars
import org.w3c.dom.Element
import pages.EasyPage
import pages.Navbar
import pages.about.AboutPage
import pages.about.SimilarityAnalysisPage
import pages.course_exercise.ExerciseSummaryPage
import pages.course_exercises_list.CourseExercisesPage
import pages.courses.CoursesPage
import pages.embed_anon_autoassess.EmbedAnonAutoassessPage
import pages.exercise_in_library.ExercisePage
import pages.exercise_library.ExerciseLibraryPage
import pages.grade_table.GradeTablePage
import pages.links.CourseJoinByLinkPage
import pages.links.RegisterLinkPage
import pages.participants.ParticipantsPage
import pages.sidenav.Sidenav
import pages.terms.TermsProxyPage
import queries.*
import rip.kspar.ezspa.*
import translation.Str
import translation.updateLanguage


private val PAGES = listOf(
    CoursesPage, CourseExercisesPage, ExerciseSummaryPage, SimilarityAnalysisPage,
    GradeTablePage, ParticipantsPage,
    ExerciseLibraryPage, ExercisePage,
    EmbedAnonAutoassessPage,
    RegisterLinkPage, CourseJoinByLinkPage,
    AboutPage, TermsProxyPage,
)

fun main() {
    consoleEgg()
    initScrollbar(getBody(), false)

    // Weird hack: strings have to be set first here to fetch possible locale from localstorage
    // but then refreshed again after auth because we might get a preference from there
    updateLanguage()

    // Start authentication as soon as possible
    doInPromise {
        val pageAuth = getPageAuth()
        if (pageAuth != EasyPage.PageAuth.NONE) {
            setSplashText("Login sisse")
            Auth.initialize(pageAuth == EasyPage.PageAuth.REQUIRED).await()
            if (Auth.authenticated) {
                setSplashText("Uuendan andmeid")
                updateAccountData()
                updateLanguage()
            }
        }
        refreshCurrentPathFromBrowser()
        buildStatics()
        EzSpa.PageManager.updatePage()
    }

    // Do stuff that does not require auth
    initApplication()
    if (!isEmbedded()) {
        EzSpa.Navigation.enableAnchorLinkInterception()
        EzSpa.Navigation.enableHistoryNavInterception()
    }
}

fun setSplashText(text: String) {
    getElemById("loading-splash-text").textContent = text
}

suspend fun buildStatics() {
    getElemById("loading-splash-container").clear()
    // Clear bg color now that background has loaded
    getBody().setAttribute("style", "")

    getMain().show()
    getHeader().show()

    if (!isEmbedded()) {
        Navbar.build()
        Sidenav.build()
        initScrollbar(getElemById("sidenav"), true)
    } else {
        // Note that appending += to innerHTML would destroy existing event handlers
        // TODO: is there an issue with having sidenav-wrap in main as well here?
//        getMain().innerHTML = """<div id="content-container" class="container"></div>"""
    }
}

private fun getPageAuth() = (EzSpa.PageManager.getCurrentPage() as EasyPage).pageAuth
private fun isEmbedded() = (EzSpa.PageManager.getCurrentPage() as EasyPage).isEmbedded

private suspend fun updateAccountData() {
    val funLog = debugFunStart("updateAccountData")

    val firstName = Auth.firstName!!
    val lastName = Auth.lastName!!
    val email = Auth.email!!

    debug { "Updating account data to [email: $email, first name: $firstName, last name: $lastName]" }

    val personalData = mapOf("first_name" to firstName, "last_name" to lastName)

    fetchEms(
        "/account/checkin",
        ReqMethod.POST,
        personalData,
        successChecker = { http200 },
        errorHandler = {
            it.handleByCode(RespError.ACCOUNT_MIGRATION_FAILED) {
                permanentErrorMessage(false) { "Kasutaja andmeid uuendades tekkis viga. Administraatorit on veast teavitatud. Palun proovi mõne aja pärast uuesti." }
                error("Account migration failed")
            }
        },
        cancellable = false
    ).await()
    debug { "Account data updated" }

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

    loadContainerQueries()
}

private fun handlePageNotFound(@Suppress("UNUSED_PARAMETER") path: String) {
    getContainer().innerHTML = tmRender(
        "tm-broken-page", mapOf(
            "title" to Str.notFoundPageTitle,
            "msg" to Str.notFoundPageMsg
        )
    )
    Sidenav.refresh(Sidenav.Spec())
}

private fun loadContainerQueries() {
    val supportsContainerQueries = try {
        document.documentElement?.asDynamic().style.container != null
    } catch (e: Throwable) {
        false
    }

    if (!supportsContainerQueries) {
        debug { "Native container queries NOT supported, using polyfill :(" }
        // Just including the reference in code forces the module to be included/loaded
        ContainerQueryPolyfill
    } else {
        debug { "Native container queries supported :)" }
    }
}

private fun initScrollbar(element: Element, autoHide: Boolean) {
    OverlayScrollbars.OverlayScrollbars(
        element,
        objOf(
            // use native if they are overlaid already like on mobile
            "showNativeOverlaidScrollbars" to true,
            "scrollbars" to
                    objOf(
                        "autoHide" to if (autoHide) "leave" else "never",
                        "autoHideDelay" to 100,
                    )
        )
    )
}

private fun consoleEgg() {
    debug {
        template(
            """
            
Hei, mis toimub?
Kas leidsid mingi vea, mille uurimiseks oli vaja brauseri konsool lahti teha? Või huvitab sind lihtsalt Lahenduse tehniline pool?
Mõlemal juhul tule räägi sellest meie Discordi serveris: {{d}}/${AppProperties.DISCORD_INVITE_ID} :-)
        """,
            "d" to "discord.gg"
        )
    }
}
