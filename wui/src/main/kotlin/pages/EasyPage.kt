package pages

import Auth
import Role
import Str
import getContainer
import kotlinx.dom.clear
import libheaders.Materialize
import pages.sidenav.Sidenav
import rip.kspar.ezspa.Page
import rip.kspar.ezspa.getElemsByClass
import tmRender

abstract class EasyPage : Page() {

    // All roles allowed by default
    open val allowedRoles: List<Role> = Role.values().asList()

    // Sidenav with no course by default
    open val sidenavSpec: Sidenav.Spec = Sidenav.Spec()

    // Title with no info by default
    open val titleSpec: Title.Spec = Title.Spec()

    final override fun assertAuthorisation() {
        super.assertAuthorisation()

        if (allowedRoles.none { it == Auth.activeRole }) {
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
        Title.replace { titleSpec }
        Sidenav.refresh(sidenavSpec)
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
}