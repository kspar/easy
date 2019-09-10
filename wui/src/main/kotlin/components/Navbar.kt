package components

import Auth
import Str
import buildStatics
import getBody
import getElemById
import libheaders.Materialize
import onVanillaClick
import spa.PageManager
import tmRender
import toJsObj

object Navbar {

    fun build() {
        val isMainRoleActive = Auth.isMainRoleActive()

        getElemById("nav-wrap").innerHTML = tmRender("tm-navbar", mapOf(
                "userName" to Auth.firstName,
                "roleIndicator" to if (isMainRoleActive) null else Str.roleChangeStudentSuffix(),
                "myCourses" to Str.topMenuCourses(),
                "account" to Str.accountData(),
                "logOut" to Str.logOut(),
                "accountLink" to Auth.createAccountUrl(),
                "logoutLink" to Auth.createLogoutUrl()))

        when {
            Auth.canToggleRole() && isMainRoleActive -> buildChangeToStudent()
            !isMainRoleActive -> buildChangeToMain()
        }

        initProfileDropdown()
    }

    private fun buildChangeToMain() {
        getElemById("role-wrap").innerHTML = tmRender("tm-role-link", mapOf(
                "changeRole" to Str.roleChangeBack(),
                "changeRoleId" to "role-link-main"
        ))

        getElemById("role-link-main").onVanillaClick(true) {
            Auth.switchRoleToMain()
            buildStatics()
            PageManager.updatePage()
        }
    }

    private fun buildChangeToStudent() {
        getElemById("role-wrap").innerHTML = tmRender("tm-role-link", mapOf(
                "changeRole" to Str.roleChangeStudent(),
                "changeRoleId" to "role-link-student"
        ))

        getElemById("role-link-student").onVanillaClick(true) {
            Auth.switchRoleToStudent()
            buildStatics()
            PageManager.updatePage()
        }
    }

    private fun initProfileDropdown() {
        Materialize.Dropdown.init(getElemById("profile-wrapper"),
                mapOf("constrainWidth" to false,
                        "coverTrigger" to false,
                        "alignment" to "right",
                        "container" to getBody()).toJsObj())
    }
}