package pages.links

import CONTENT_CONTAINER_ID
import Icons
import components.ToastThing
import components.form.OldButtonComp
import dao.CoursesStudentDAO
import kotlinx.coroutines.await
import pages.Title
import pages.course_exercises_list.CourseExercisesPage
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.EzSpa
import rip.kspar.ezspa.doInPromise
import template

class CourseJoinByLinkComp(
    private val inviteId: String,
) : Component(null, CONTENT_CONTAINER_ID) {

    private val joinBtn = OldButtonComp(
        OldButtonComp.Type.PRIMARY, "Liitu", Icons.check,
        onClick = ::joinCourse,
        parent = this
    )

    private var courseTitle: String? = null


    override val children: List<Component>
        get() = listOf(joinBtn)

    override fun create() = doInPromise {
        val (id, title) = CoursesStudentDAO.getCourseTitleByLink(inviteId).await() ?: return@doInPromise
        courseTitle = title
        Title.update { it.parentPageTitle = title }

        val alreadyEnrolled = CoursesStudentDAO.getMyCourses().await().any { it.id == id }
        if (alreadyEnrolled) {
            EzSpa.PageManager.navigateTo(CourseExercisesPage.link(id))
            error("Do not render")
        }
    }

    override fun render() = template(
        """
            <ez-join-course>
                <div class="loading-wrap">
                    <div class="antennaball light"></div>
                    <div class="antenna"></div>
                    <div class="robot">
                        <div class="robot-eye blinking"></div>
                        <div class="robot-eye blinking"></div>
                    </div>
                </div>
                {{#course}}
                    <h3>{{course}}</h3>
                    <p style='margin-bottom: 3rem;'>{{joinStr}}</p>
                    $joinBtn
                {{/course}}
                {{^course}}
                    <h3>{{invalidHeading}}</h3>
                    <p>{{invalidText}}</p>
                {{/course}}
            </ez-join-course>
        """.trimIndent(),
        "joinStr" to """Kas soovid liituda kursusega "$courseTitle"?""",
        "invalidHeading" to "Kehtetu link",
        "invalidText" to "See link on vale või oma kehtivuse kaotanud. ¯\\_(ツ)_/¯",
        "course" to courseTitle,
    )

    private suspend fun joinCourse() {
        val courseId = CoursesStudentDAO.joinByLink(inviteId).await().course_id
        EzSpa.PageManager.navigateTo(CourseExercisesPage.link(courseId))
        ToastThing("Tere tulemast kursusele!")
    }
}