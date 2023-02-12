package pages.links

import Auth
import PageName
import debug
import kotlinx.browser.window
import pages.EasyPage
import pages.courses.CoursesPage
import queries.createQueryString
import queries.getCurrentQueryParamValue
import rip.kspar.ezspa.EzSpa

object RegisterLinkPage : EasyPage() {
    override val pageName = PageName.LINK_REGISTER

    override val pathSchema = "/link/register"

    override val pageAuth = PageAuth.OPTIONAL

    override fun build(pageStateStr: String?) {
        super.build(pageStateStr)

        val email = getCurrentQueryParamValue("email")

        if (Auth.authenticated) {
            if (Auth.email == email || email == null) {
                // Correct account already created
                debug { "Account with email $email already logged in" }
                EzSpa.PageManager.navigateTo(CoursesPage.link())
            } else {
                // Logged in with different account
                debug { "Logged in with email: ${Auth.email} but required email: $email" }
                // TODO: message with logout option?
            }
        }

        val registerUrl = Auth.createRegisterUrl() + createQueryString(
            true, mapOf(
                "ezHintEmail" to email,
                "ezHintEmailDisabled" to "1",
                "ezHintUsername" to email,
            )
        )

        debug { "Navigating to register URL: $registerUrl" }
        window.location.href = registerUrl
    }
}