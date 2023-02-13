package pages.links

import AppProperties
import Auth
import PageName
import debug
import kotlinx.browser.window
import pages.EasyPage
import pages.courses.CoursesPage
import queries.createQueryString
import queries.getCurrentQueryParamValue
import rip.kspar.ezspa.EzSpa
import rip.kspar.ezspa.objOf

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
                return
            } else {
                // Logged in with different account
                debug { "Logged in with email: ${Auth.email} but required email: $email" }
                // Log out, redirects back to this page
                Auth.logout()
                return
            }
        }

        // Not logged in, redirect to register
        val registerUrl = Auth.createRegisterUrl(
            // Should not redirect back to this page afterwards to prevent possible loops
            objOf("redirectUri" to AppProperties.WUI_ROOT)
        ) + createQueryString(
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