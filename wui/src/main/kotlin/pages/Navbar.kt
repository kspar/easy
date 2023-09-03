package pages

import Auth
import Icons
import libheaders.Materialize
import rip.kspar.ezspa.*
import template
import translation.Str
import translation.activeLanguage
import translation.changeLanguage

object Navbar {

    private val logoutId = IdGenerator.nextId()
    private val changeLanguageId = IdGenerator.nextId()

    fun build() {
        getElemById("nav-wrap").innerHTML = template(
            """
                <nav class="navbar">
                    <div class="topnav container" role="navigation">
                        <div class="content-container">
                            <div class="left-cluster">
                                <div>
                                    <a href="#!" class="sidenav-trigger" data-target="sidenav">{{{sidenavIcon}}}</a>
                                </div>
                                <a href="/courses" class="logo">
                                    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path d="M11 0l6 4.8V0zm6 4.8V7 4.8zM7 0v10.1h10V7L8.4 0zm6.9 13.9h10V24H14zM0 13.9h10.1V24H.1z"/></svg>
                                    <span class="text">LAHENDUS</span>
                                </a>
                            </div>
                            <!-- Search here -->
                            <div class="right-cluster">
                                <div id="profile-wrap" class="dropdown-trigger profile-wrap" data-target="profile-dropdown">
                                    <a href="#!">
                                        <i class="profile-logo left material-icons">account_circle</i>
                                        <span class="profile-name">{{userName}}</span>
                                    </a>
                                </div>
                            </div>
                        </div>
                    </div>
                </nav>
                <!-- Profile dropdown menu structure -->
                <ul id="profile-dropdown" class="dropdown-content">
                    <li><a id='$changeLanguageId'>{{otherLanguage}}</a></li>
                    <li><a href="{{accountLink}}">{{account}}</a></li>
                    <li><a id='$logoutId'>{{logOut}}</a></li>
                </ul>
            """.trimIndent(),
            "otherLanguage" to Str.otherLanguage,
            "sidenavIcon" to Icons.hamburgerMenu,
            "userName" to (Auth.firstName ?: ""),
            "account" to Str.accountData,
            "accountLink" to Auth.createAccountUrl(objOf("locale" to activeLanguage.localeId)),
            "logOut" to Str.logOut,
        )

        getElemById(changeLanguageId).onVanillaClick(true) {
            changeLanguage()
        }

        getElemById(logoutId).onVanillaClick(true) {
            Auth.logout(objOf("locale" to activeLanguage.localeId))
        }

        initProfileDropdown()
    }

    private fun initProfileDropdown() {
        Materialize.Dropdown.init(
            getElemById("profile-wrap"),
            mapOf(
                "constrainWidth" to false,
                "coverTrigger" to false,
                "alignment" to "right",
                "container" to getBody()
            ).toJsObj(),
        )
    }
}