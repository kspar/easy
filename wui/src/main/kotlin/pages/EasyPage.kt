package pages

import Auth
import Icons
import Role
import ScrollPosition
import components.ToastIds
import components.ToastThing
import components.activeToasts
import debug
import getContainer
import getWindowScrollPosition
import kotlinx.dom.clear
import kotlinx.serialization.Serializable
import libheaders.Materialize
import pages.course_exercises_list.CourseExercisesPage
import pages.courses.CoursesPage
import pages.sidenav.Sidenav
import parseTo
import rip.kspar.ezspa.EzSpa
import rip.kspar.ezspa.Page
import rip.kspar.ezspa.getElemsByClass
import stringify
import translation.Str

abstract class EasyPage : Page() {

    enum class PageAuth { REQUIRED, OPTIONAL, NONE }

    /**
     * Hint to application whether authentication should be required/started before the user navigates to it.
     * Changing this value provides no security. Setting it to NONE will allow unauthenticated users to navigate
     * to this page; setting it to REQUIRED will cause the application to start authentication for unauthenticated users
     * before navigating here; setting it to OPTIONAL will populate user info if the user is already authenticated but
     * will not enforce login if they're not.
     */
    open val pageAuth = PageAuth.REQUIRED

    /**
     * Whether the page is meant to be embedded i.e. not independently visited in the browser.
     * If true, static elements like the navbar are not rendered.
     */
    open val isEmbedded = false

    // All roles allowed by default
    open val allowedRoles: List<Role> = Role.values().asList()

    // Sidenav with no course by default
    open val sidenavSpec: Sidenav.Spec = Sidenav.Spec()

    // Title with no info by default
    open val titleSpec: Title.Spec = Title.Spec()

    // Course ID if this page involves a course
    open val courseId: String? = null

    final override fun assertAuthorisation() {
        super.assertAuthorisation()

        if (pageAuth == PageAuth.REQUIRED) {
            if (!Auth.authenticated) {
                error("Not authenticated")
            }

            if (allowedRoles.none { it == Auth.activeRole }) {
                val c = courseId
                if (c != null) {
                    debug { "Navigate to course exercise page" }
                    EzSpa.PageManager.navigateTo(CourseExercisesPage.link(c))
                } else {
                    debug { "Navigate to courses page" }
                    EzSpa.PageManager.navigateTo(CoursesPage.link())
                }

                ToastThing(
                    Str.noPermissionForPageMsg, icon = Icons.errorUnf, displayLengthSec = 10,
                    id = ToastIds.noPermissionForPage
                )
                Sidenav.refresh(Sidenav.Spec())
                error("Role ${Auth.activeRole} not allowed")
            }
        }
    }

    override fun clear() {
        super.clear()
        getContainer().clear()
    }

    override fun build(pageStateStr: String?) {
        if (!isEmbedded) {
            Title.replace { titleSpec }
            Sidenav.refresh(sidenavSpec)
        }
    }

    override fun destruct() {
        super.destruct()

        // Destroy tooltips
        getElemsByClass("tooltipped")
            .map { Materialize.Tooltip.getInstance(it) }
            .forEach { it?.destroy() }

        // Dismiss toasts
        activeToasts.forEach { it.value.dismiss() }
    }


    @Serializable
    data class ScrollPosState(val scrollPosition: ScrollPosition)

    fun updateStateWithScrollPos() {
        updateState(ScrollPosState.serializer().stringify(ScrollPosState(getWindowScrollPosition())))
    }

    fun String?.getScrollPosFromState(): ScrollPosition? =
        this?.parseTo(ScrollPosState.serializer())?.scrollPosition

}