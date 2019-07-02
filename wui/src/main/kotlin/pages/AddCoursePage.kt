package pages

import JsonUtil
import PageName
import ReqMethod
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
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLTextAreaElement
import spa.Page
import spa.PageManager.navigateTo
import tmRender


object AddCoursePage : Page<Nothing>() {

    override val pageName: PageName
        get() = PageName.ADD_COURSE

    override fun pathMatches(path: String): Boolean =
            path.matches("^/courses/new/?$")

    override fun build(pageState: Nothing?) {
        val funLog = debugFunStart("AddCoursePage.build")
        getContainer().innerHTML = tmRender("tm-add-course", mapOf(
                "newCourseName" to Str.newCourseName,
                "addNewCourse" to Str.addNewCourse))

        getElemByIdAs<HTMLElement>("add-course-button").onclick = {
            val title = getElemByIdAs<HTMLTextAreaElement>("course-title").value
            debug { "Got new course title: $title" }

            MainScope().launch {
                val resp = fetchEms("/admin/courses", ReqMethod.POST, mapOf(Pair("title", title))).await()

                if (!resp.http200) {
                    errorMessage { Str.courseCreationFailed }
                    error("Creation of new course failed")
                }

                val courseID = JsonUtil.parseJson(resp.text().await()).jsonObject.getValue("id").primitive.content
                debug { "Saved new course with id $courseID" }

                navigateTo("/courses/$courseID/exercises")
            }
        }
        funLog?.end()
    }
}
