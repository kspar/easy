package pages.course_exercise

import Auth
import PageName
import Role
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.dom.addClass
import kotlinx.dom.removeClass
import pages.EasyPage
import pages.course_exercise.student.StudentCourseExerciseComp
import pages.course_exercise.teacher.TeacherCourseExerciseComp
import pages.sidenav.ActivePage
import pages.sidenav.Sidenav
import queries.createQueryString
import queries.getCurrentQueryParamValue
import restore
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.Navigation
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.getHtml

object ExerciseSummaryPage : EasyPage() {

    private var rootComp: Component? = null

    override val pageName: Any
        get() = PageName.EXERCISE_SUMMARY

    override val sidenavSpec: Sidenav.Spec
        get() = Sidenav.Spec(pathParams.courseId, ActivePage.STUDENT_EXERCISE)

    override val pathSchema = "/courses/{courseId}/exercises/{courseExerciseId}/**"

    data class PathParams(val courseId: String, val courseExerciseId: String)

    private val pathParams: PathParams
        get() = parsePathParams().let {
            PathParams(it["courseId"], it["courseExerciseId"])
        }

    override val courseId
        get() = pathParams.courseId

    val courseExerciseId
        get() = pathParams.courseExerciseId

    override fun build(pageStateStr: String?) {
        super.build(pageStateStr)
        val scrollPosition = pageStateStr.getScrollPosFromState()

        doInPromise {
            getHtml().addClass("wui3", "full-width")

            rootComp = when (Auth.activeRole) {
                Role.STUDENT -> {
                    Sidenav.refresh(sidenavSpec, true)
                    StudentCourseExerciseComp(courseId, courseExerciseId, ::setWildcardPath)

                }

                Role.TEACHER, Role.ADMIN -> {
                    TeacherCourseExerciseComp(
                        courseId,
                        courseExerciseId,
                        getCurrentQueryParamValue("student"),
                        getCurrentQueryParamValue("tab"),
                        ::setWildcardPath
                    )
                }
            }

            // FIXME: commented out for easier testing
//            updateUrl(window.location.pathname)

            rootComp!!.createAndBuild().await()
            scrollPosition?.restore()
            Navigation.catchNavigation {
                rootComp!!.hasUnsavedChanges()
            }
        }
    }

    override fun onPreNavigation() {
        updateStateWithScrollPos()
    }

    override fun destruct() {
        super.destruct()
        rootComp?.destroy()
        rootComp = null
        Navigation.stopNavigationCatching()
        getHtml().removeClass("wui3", "full-width")
    }

    private fun setWildcardPath(wildcardPathSuffix: String) {
        updateUrl(link(courseId, courseExerciseId) + wildcardPathSuffix + window.location.search)
    }

    fun link(courseId: String, courseExerciseId: String, openSubmissionStudentId: String? = null) =
        constructPathLink(mapOf("courseId" to courseId, "courseExerciseId" to courseExerciseId)) +
                if (openSubmissionStudentId != null)
                    createQueryString("student" to openSubmissionStudentId)
                else ""
}
