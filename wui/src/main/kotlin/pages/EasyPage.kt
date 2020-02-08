package pages

import Auth
import Role
import Str
import getContainer
import getElemsByClass
import libheaders.Materialize
import spa.Page
import tmRender
import kotlin.dom.clear

abstract class EasyPage : Page() {

    // All roles allowed by default
    open val allowedRoles: List<Role> = Role.values().asList()

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