package pages

import Auth
import Role
import Str
import getContainer
import kotlinx.dom.clear
import libheaders.Materialize
import pages.leftbar.Leftbar
import rip.kspar.ezspa.Page
import rip.kspar.ezspa.getElemsByClass
import tmRender

abstract class EasyPage : Page() {

    // All roles allowed by default
    open val allowedRoles: List<Role> = Role.values().asList()

    // Leftbar with no course by default
    open val leftbarConf: Leftbar.Conf = Leftbar.Conf(null)

    final override fun assertAuthorisation() {
        super.assertAuthorisation()

        if (allowedRoles.none { it == Auth.activeRole }) {
            getContainer().innerHTML = tmRender("tm-no-access-page", mapOf(
                    "title" to Str.noPermissionForPageTitle(),
                    "msg" to Str.noPermissionForPageMsg()
            ))
            error("User is not one of $allowedRoles")
        }
    }

    override fun clear() {
        super.clear()
        getContainer().clear()
    }

    override fun build(pageStateStr: String?) {
        Leftbar.refresh(leftbarConf)
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