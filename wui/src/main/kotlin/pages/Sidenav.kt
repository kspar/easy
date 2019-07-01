package pages

import Str
import getElemById
import libheaders.Materialize
import objOf
import tmRender

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
        initSidenav()

    }


    private fun initSidenav() {
        Materialize.Sidenav.init(getElemById("slide-out"), objOf())
    }

}