package pages

import Auth
import Str
import libheaders.Materialize
import pages.leftbar.Leftbar
import rip.kspar.ezspa.getBody
import rip.kspar.ezspa.getElemById
import rip.kspar.ezspa.onVanillaClick
import tmRender
import toJsObj

object Navbar {

    fun build() {
        getElemById("nav-wrap").innerHTML = tmRender(
                "tm-navbar",
                "userName" to Auth.firstName,
                "account" to Str.accountData(),
                "accountLink" to Auth.createAccountUrl(),
                "logOut" to Str.logOut(),
                "logOutLink" to Auth.createLogoutUrl(),
        )

        initProfileDropdown()

        getElemById("sidenav-toggle").onVanillaClick(true) {
            Leftbar.handleLeftbarToggleClick()
        }
    }

    private fun initProfileDropdown() {
        Materialize.Dropdown.init(
                getElemById("profile-wrap"),
                mapOf("constrainWidth" to false,
                        "coverTrigger" to false,
                        "alignment" to "right",
                        "container" to getBody()).toJsObj(),
        )
    }
}