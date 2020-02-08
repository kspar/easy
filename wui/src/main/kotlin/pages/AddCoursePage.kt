package pages

import PageName
import Role
import Str
import debug
import debugFunStart
import getContainer
import getElemByIdAs
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLTextAreaElement
import queries.ReqMethod
import queries.fetchEms
import queries.http200
import queries.parseTo
import spa.PageManager.navigateTo
import tmRender


object AddCoursePage : EasyPage() {

    @Serializable
    data class AdminCourse(val id: String)

    override val pageName: PageName
        get() = PageName.ADD_COURSE

    override val allowedRoles: List<Role>
        get() = listOf(Role.ADMIN)

    override fun pathMatches(path: String): Boolean =
            path.matches("^/courses/new/?$")

    override fun build(pageStateStr: String?) {
        val funLog = debugFunStart("AddCoursePage.build")

        getContainer().innerHTML = tmRender("tm-add-course", mapOf(
                "newCourseName" to Str.newCourseName(),
                "addNewCourse" to Str.addNewCourse()))

        getElemByIdAs<HTMLButtonElement>("add-course-button").onclick = {
            val title = getElemByIdAs<HTMLTextAreaElement>("course-title").value
            debug { "Got new course title: $title" }

            MainScope().launch {
                val resp = fetchEms("/admin/courses", ReqMethod.POST, mapOf("title" to title),
                        successChecker = { http200 }).await()

                val course: AdminCourse = resp.parseTo(AdminCourse.serializer()).await()
                val courseID = course.id
                debug { "Saved new course with id $courseID" }

                navigateTo("/courses/$courseID/exercises")
            }
        }
        funLog?.end()
    }
}
