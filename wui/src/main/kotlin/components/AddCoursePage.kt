package components

import Auth
import PageName
import ReqMethod
import Role
import Str
import debug
import debugFunStart
import errorMessage
import fetchEms
import getContainer
import getElemByIdAs
import http200
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLTextAreaElement
import parseTo
import spa.Page
import spa.PageManager.navigateTo
import tmRender


object AddCoursePage : Page() {

    @Serializable
    data class AdminCourse(val id: String)

    override val pageName: PageName
        get() = PageName.ADD_COURSE

    override fun pathMatches(path: String): Boolean =
            path.matches("^/courses/new/?$")

    override fun build(pageStateStr: String?) {
        val funLog = debugFunStart("AddCoursePage.build")

        if (Auth.activeRole != Role.ADMIN) {
            errorMessage { Str.noPermissionForPage }
            error("User is not admin")
        }

        getContainer().innerHTML = tmRender("tm-add-course", mapOf(
                "newCourseName" to Str.newCourseName,
                "addNewCourse" to Str.addNewCourse))

        getElemByIdAs<HTMLButtonElement>("add-course-button").onclick = {
            val title = getElemByIdAs<HTMLTextAreaElement>("course-title").value
            debug { "Got new course title: $title" }

            MainScope().launch {
                val resp = fetchEms("/admin/courses", ReqMethod.POST, mapOf("title" to title)).await()

                if (!resp.http200) {
                    errorMessage { Str.courseCreationFailed }
                    error("Creation of new course failed")
                }

                val course: AdminCourse = resp.parseTo(AdminCourse.serializer()).await()
                val courseID = course.id
                debug { "Saved new course with id $courseID" }

                navigateTo("/courses/$courseID/exercises")
            }
        }
        funLog?.end()
    }
}
