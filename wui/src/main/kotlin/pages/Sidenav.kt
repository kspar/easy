package pages

import Str
import getElemById
import getElemsByClass
import libheaders.Materialize
import objOf
import tmRender
import kotlin.dom.addClass
import kotlin.dom.clear
import kotlin.dom.removeClass

object Sidenav {

    fun build(courseId: String) {

        // TODO: Check if exists already

        // TODO: Check role

        val sidenavHtml = tmRender("tm-sidenav",
                mapOf("courseId" to courseId,
                        "header" to Str.sidenavHeader,
                        "newExercise" to Str.newExercise,
                        "addExistingExercise" to Str.addExistingExercise,
                        "participants" to Str.participants,
                        "grades" to Str.grades
                ))
        getElemById("sidenav-wrap").innerHTML = sidenavHtml

        getElemsByClass("container").forEach {
            it.addClass("sidenav-container")
        }
        initSidenav()

    }

    fun remove() {
        getElemById("sidenav-wrap").clear()
        getElemsByClass("container").forEach {
            it.removeClass("sidenav-container")
        }
    }


    private fun initSidenav() {
        Materialize.Sidenav.init(getElemById("slide-out"), objOf())
    }

}