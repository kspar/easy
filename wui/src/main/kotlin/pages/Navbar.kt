package pages

import Auth
import Str
import buildStatics
import getBody
import getElemById
import getElemByIdOrNull
import libheaders.Materialize
import onVanillaClick
import spa.PageManager
import tmRender
import toJsObj

object Navbar {

    fun build() {
        val isMainRoleActive = Auth.isMainRoleActive()

        val rolesList = mutableListOf<Map<String, Any?>>().apply {
            if (Auth.isAdmin())
                add(mapOf(
                        "name" to Str.roleListAdmin(),
                        "active" to Auth.isAdminActive(),
                        "linkId" to "role-switch-admin"
                ))

            if (!Auth.isAdmin() && Auth.isTeacher())
                add(mapOf(
                        "name" to Str.roleListTeacher(),
                        "active" to Auth.isTeacherActive(),
                        "linkId" to "role-switch-teacher"
                ))

            if (Auth.isStudent())
                add(mapOf(
                        "name" to Str.roleListStudent(),
                        "active" to Auth.isStudentActive(),
                        "linkId" to "role-switch-student"
                ))
        }


        getElemById("nav-wrap").innerHTML = tmRender("tm-navbar", mapOf(
                "userName" to Auth.firstName,
                "roleIndicator" to if (isMainRoleActive) null else Str.roleChangeStudentSuffix(),
                "myCourses" to Str.topMenuCourses(),
                "activeRoleLabel" to Str.activeRoleLabel(),
                "hasRoles" to (rolesList.size > 1),
                "roles" to rolesList.toJsObj(),
                "account" to Str.accountData(),
                "accountLink" to Auth.createAccountUrl(),
                "logOut" to Str.logOut(),
                "logoutLink" to Auth.createLogoutUrl()))

        getElemByIdOrNull("role-switch-admin")?.onVanillaClick(true) {
            Auth.switchRoleToAdmin()
            buildStatics()
            PageManager.updatePage()
        }

        getElemByIdOrNull("role-switch-teacher")?.onVanillaClick(true) {
            Auth.switchRoleToTeacher()
            buildStatics()
            PageManager.updatePage()
        }

        getElemByIdOrNull("role-switch-student")?.onVanillaClick(true) {
            Auth.switchRoleToStudent()
            buildStatics()
            PageManager.updatePage()
        }

        initProfileDropdown()
    }


    private fun initProfileDropdown() {
        Materialize.Dropdown.init(getElemById("profile-wrapper"),
                mapOf("constrainWidth" to false,
                        "coverTrigger" to false,
                        "alignment" to "right",
                        "container" to getBody()).toJsObj())
    }
}