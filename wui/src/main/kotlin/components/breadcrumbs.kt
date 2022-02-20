package components

import Str
import pages.course_exercises.CourseExercisesPage
import pages.courses.CoursesPage
import pages.exercise_library.ExerciseLibraryPage
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import tmRender


data class Crumb(val label: String, val href: String? = null) {
    companion object {
        val myCourses = Crumb(Str.myCourses(), CoursesPage.link())
        fun courseExercises(courseId: String, courseTitle: String) = Crumb(courseTitle, CourseExercisesPage.link(courseId))
        val exercises = Crumb(Str.exerciseLibrary(), ExerciseLibraryPage.link())
    }
}

class BreadcrumbsComp(
    private val crumbs: List<Crumb>,
    parent: Component?,
    dstId: String = IdGenerator.nextId()
) : Component(parent, dstId) {

    override fun render(): String = tmRender("t-c-breadcrumbs",
        "crumbs" to crumbs.map {
            mapOf(
                "label" to it.label,
                "href" to it.href
            )
        }
    )
}
