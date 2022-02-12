package pages.sidenav

import Icons
import Role
import Str
import kotlinx.coroutines.await
import pages.course_exercises.CourseExercisesPage
import pages.courses.CoursesPage
import pages.exercise_library.ExerciseLibraryPage
import rip.kspar.ezspa.*
import tmRender

class SidenavGeneralSectionComp(
    private val activeRole: Role,
    parent: Component,
    dstId: String,
) : SidenavSectionComp(parent, dstId) {

    private val coursesItemId = IdGenerator.nextId()
    private val libItemId = IdGenerator.nextId()
    private val articlesItemId = IdGenerator.nextId()

    private val newCourseModal = NewCourseModalComp(this, "new-course-modal-dst-id")

    private val newCourseLinkId = IdGenerator.nextId()

    override val children = listOf(newCourseModal)

    override fun render(): String = tmRender(
        "t-c-sidenav-general-section",
        "isTeacherOrAdmin" to listOf(Role.TEACHER, Role.ADMIN).contains(activeRole),
        "isAdmin" to (activeRole == Role.ADMIN),
        "coursesId" to coursesItemId,
        "libId" to libItemId,
        "articlesId" to articlesItemId,
        "coursesLink" to CoursesPage.link(),
        "libLink" to ExerciseLibraryPage.link(),
        "articlesLink" to "#!",
        "newCourseLinkId" to newCourseLinkId,
        "coursesIcon" to Icons.courses,
        "libIcon" to Icons.library,
        "articlesIcon" to Icons.articles,
        "newCourseIcon" to Icons.newCourse,
        "coursesLabel" to Str.myCourses(),
        "libLabel" to Str.exerciseLibrary(),
        "articlesLabel" to "Artiklid",
        "newCourseLabel" to "Uus kursus",
    )

    override fun postRender() {
        getElemByIdOrNull(newCourseLinkId)?.onVanillaClick(false) {
            val courseId = newCourseModal.openWithClosePromise().await()
            courseId?.let {
                EzSpa.PageManager.navigateTo(CourseExercisesPage.link(courseId))
            }
        }
    }

    override fun getActivePageItemIds() = mapOf(
        ActivePage.MY_COURSES to coursesItemId,
        ActivePage.LIBRARY to libItemId,
        ActivePage.ARTICLES to articlesItemId,
    )
}