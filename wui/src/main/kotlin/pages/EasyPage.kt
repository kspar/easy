package pages

import Auth
import Role
import ScrollPosition
import Str
import getContainer
import getWindowScrollPosition
import kotlinx.dom.clear
import kotlinx.serialization.Serializable
import libheaders.Materialize
import pages.sidenav.Sidenav
import parseTo
import rip.kspar.ezspa.Page
import rip.kspar.ezspa.getElemsByClass
import stringify
import tmRender

abstract class EasyPage : Page() {

    /**
     * Hint to application whether authentication should be required/started before the user navigates to it.
     * Changing this value provides no security. Setting it to false will allow unauthenticated users to navigate
     * to this page; setting it to true will cause the application to start authentication for unauthenticated users
     * before navigating here.
     */
    open val doesRequireAuthentication = true

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

    final override fun assertAuthorisation() {
        super.assertAuthorisation()

        if (doesRequireAuthentication && allowedRoles.none { it == Auth.activeRole }) {
            getContainer().innerHTML = tmRender(
                "tm-no-access-page", mapOf(
                    "title" to Str.noPermissionForPageTitle(),
                    "msg" to Str.noPermissionForPageMsg()
                )
            )
            Sidenav.refresh(Sidenav.Spec())
            error("User is not one of $allowedRoles")
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
        Materialize.Toast.dismissAll()
    }


    @Serializable
    data class ScrollPosState(val scrollPosition: ScrollPosition)

    fun updateStateWithScrollPos() {
        updateState(ScrollPosState.serializer().stringify(ScrollPosState(getWindowScrollPosition())))
    }

    fun String?.getScrollPosFromState(): ScrollPosition? =
        this?.parseTo(ScrollPosState.serializer())?.scrollPosition

}