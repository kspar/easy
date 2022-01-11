package pages.sidenav

import Role
import rip.kspar.ezspa.Component
import tmRender

class SidenavTrailerSectionComp(
    private val activeRole: Role,
    parent: Component,
    dstId: String,
) : Component(parent, dstId) {

    override fun render(): String = tmRender(
        "t-c-sidenav-trailer-section",
        "isAdmin" to (activeRole == Role.ADMIN),
        "issueTrackLink" to AppProperties.ISSUE_TRACKER_URL,
        "issueTrackIcon" to Icons.issueTracker,
        "issueTrackLabel" to "YouTrack",
        "idpLink" to AppProperties.KEYCLOAK_ADMIN_CONSOLE_URL,
        "idpIcon" to Icons.idpAdminConsole,
        "idpLabel" to "Keycloak admin",
        "accountSettingsLink" to Auth.createAccountUrl(),
        "accountSettingsIcon" to Icons.settings,
        "accountSettingsLabel" to "Konto seaded",
        "logOutLink" to Auth.createLogoutUrl(),
        "logOutIcon" to Icons.logout,
        "logOutLabel" to "Logi välja",
    )
}