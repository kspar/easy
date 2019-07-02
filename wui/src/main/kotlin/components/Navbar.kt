package components

import Keycloak
import Str
import buildStatics
import getBody
import getElemById
import getElemByIdAs
import libheaders.Materialize
import org.w3c.dom.HTMLAnchorElement
import spa.PageManager
import tmRender
import toJsObj
import kotlin.dom.clear

object Navbar {

    fun build() {
        val navHtml = tmRender("tm-navbar", mapOf(
                "userName" to Keycloak.firstName,
                "myCourses" to Str.myCourses,
                "account" to Str.accountData,
                "logOut" to Str.logOut,
                "accountLink" to Keycloak.createAccountUrl(),
                "logoutLink" to Keycloak.createLogoutUrl()))
        getElemById("nav-wrap").innerHTML = navHtml

        if (Keycloak.isMainRoleActive())
            buildRoleChangeToStudentIfPossible()
        else
            buildRoleChangeBackToMainRole()

        initProfileDropdown()
    }

    private fun buildRoleChangeBackToMainRole() {
        val roleToMainHtml = tmRender("tm-role-link", mapOf(
                "changeRole" to Str.roleChangeBack,
                "changeRoleId" to "role-link-main"
        ))
        getElemById("role-wrap").innerHTML = roleToMainHtml

        getElemByIdAs<HTMLAnchorElement>("role-link-main").onclick = {
            buildStatics()
            getElemById("profile-role").clear()
            buildRoleChangeToStudentIfPossible()
            initProfileDropdown()
            Keycloak.switchRoleToMain()
            PageManager.updatePage()
            it
        }
    }

    private fun buildRoleChangeToStudentIfPossible() {
        if (Keycloak.canToggleRole()) {
            val roleToStudentHtml = tmRender("tm-role-link", mapOf(
                    "changeRole" to Str.roleChangeStudent,
                    "changeRoleId" to "role-link-student"
            ))
            getElemById("role-wrap").innerHTML = roleToStudentHtml

            getElemByIdAs<HTMLAnchorElement>("role-link-student").onclick = {
                buildStatics()
                getElemById("profile-role").textContent = Str.roleCHangeStudentSuffix
                buildRoleChangeBackToMainRole()
                initProfileDropdown()
                Keycloak.switchRoleToStudent()
                PageManager.updatePage()
                it
            }
        }
    }

    private fun initProfileDropdown() {
        Materialize.Dropdown.init(getElemById("profile-wrapper"),
                mapOf("constrainWidth" to false,
                        "coverTrigger" to false,
                        "container" to getBody()).toJsObj())
    }
}