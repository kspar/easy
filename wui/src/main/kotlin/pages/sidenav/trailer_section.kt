package pages.sidenav

import AppProperties
import Auth
import Icons
import Role
import Str
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.getElemById
import rip.kspar.ezspa.onVanillaClick
import template

class SidenavTrailerSectionComp(
    private val activeRole: Role,
    parent: Component,
    dstId: String,
) : Component(parent, dstId) {

    val logoutId = IdGenerator.nextId()

    override fun render() = template(
        """
            <li><div class="divider"></div></li>
            {{#isAdmin}}
                <li><a href="{{idpLink}}" class="waves-effect sidenav-close">{{{idpIcon}}}{{idpLabel}}</a></li>
                <li><a href="{{issueTrackLink}}" class="waves-effect sidenav-close">{{{issueTrackIcon}}}{{issueTrackLabel}}</a></li>
            {{/isAdmin}}
            <li><a href="{{accountSettingsLink}}" class="waves-effect sidenav-close">{{{accountSettingsIcon}}}{{accountSettingsLabel}}</a></li>
            <li><a id='$logoutId' class="waves-effect sidenav-close">{{{logOutIcon}}}{{logOutLabel}}</a></li>
        """.trimIndent(),
        "isAdmin" to (activeRole == Role.ADMIN),
        "issueTrackLink" to AppProperties.ISSUE_TRACKER_URL,
        "issueTrackIcon" to Icons.issueTracker,
        "issueTrackLabel" to "YouTrack",
        "idpLink" to AppProperties.KEYCLOAK_ADMIN_CONSOLE_URL,
        "idpIcon" to Icons.idpAdminConsole,
        "idpLabel" to "Keycloak admin",
        "accountSettingsLink" to Auth.createAccountUrl(),
        "accountSettingsIcon" to Icons.settings,
        "accountSettingsLabel" to Str.accountData(),
        "logOutIcon" to Icons.logout,
        "logOutLabel" to Str.logOut(),
    )

    override fun postRender() {
        getElemById(logoutId).onVanillaClick(true) {
            Auth.logout()
        }
    }
}