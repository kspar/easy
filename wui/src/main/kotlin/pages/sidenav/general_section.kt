package pages.sidenav

import Icons
import Role
import Str
import kotlinx.coroutines.await
import pages.course_exercises_list.CourseExercisesPage
import pages.courses.CoursesPage
import pages.exercise_library.ExerciseLibraryPage
import rip.kspar.ezspa.*
import template

class SidenavGeneralSectionComp(
    private val activeRole: Role,
    parent: Component,
    dstId: String,
) : SidenavSectionComp(parent, dstId) {

    private val coursesItemId = IdGenerator.nextId()
    private val libItemId = IdGenerator.nextId()
    private val articlesItemId = IdGenerator.nextId()

    private val newCourseModal = CreateCourseModalComp(this)

    private val newCourseLinkId = IdGenerator.nextId()

    override val children = listOf(newCourseModal)

    override fun render(): String = template(
        """
            <li><div class="divider"></div></li>
            <li id="{{coursesId}}"><a href="{{coursesLink}}" class="sidenav-close">{{{coursesIcon}}}{{coursesLabel}}</a></li>
            {{#isTeacherOrAdmin}}
                <li id="{{libId}}"><a href="{{libLink}}" class="sidenav-close">{{{libIcon}}}{{libLabel}}</a></li>
            {{/isTeacherOrAdmin}}
            {{#isAdmin}}
        <!--        <li id="{{articlesId}}"><a href="{{articlesLink}}" class="sidenav-close">{{{articlesIcon}}}{{articlesLabel}}</a></li>-->
                <li><a id="{{newCourseLinkId}}" class="sidenav-close">{{{newCourseIcon}}}{{newCourseLabel}}</a></li>
            {{/isAdmin}}
            <ez-dst id="{{newCourseModalDst}}"></ez-dst>
        """.trimIndent(),
        "isTeacherOrAdmin" to listOf(Role.TEACHER, Role.ADMIN).contains(activeRole),
        "isAdmin" to (activeRole == Role.ADMIN),
        "coursesId" to coursesItemId,
        "libId" to libItemId,
        "articlesId" to articlesItemId,
        "coursesLink" to CoursesPage.link(),
        "libLink" to ExerciseLibraryPage.linkToRoot(),
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
        "newCourseModalDst" to newCourseModal.dstId,
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