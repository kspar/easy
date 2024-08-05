package pages.links

import CONTENT_CONTAINER_ID
import Icons
import components.ButtonComp
import components.ToastThing
import dao.CoursesStudentDAO
import kotlinx.coroutines.await
import pages.Title
import pages.course_exercises_list.CourseExercisesPage
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.EzSpa
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.dstIfNotNull
import template
import translation.Str

class CourseJoinByLinkComp(
    inviteId: String,
    private val isMoodleCourse: Boolean,
) : Component(null, CONTENT_CONTAINER_ID) {

    private val inviteId = inviteId.uppercase()
    private var joinBtn: ButtonComp? = null
    private var courseTitle: String? = null


    override val children: List<Component>
        get() = listOfNotNull(joinBtn)

    override fun create() = doInPromise {
        val (id, title) = if (isMoodleCourse)
            CoursesStudentDAO.getMoodleCourseTitleByLink(inviteId).await() ?: return@doInPromise
        else
            CoursesStudentDAO.getCourseTitleByLink(inviteId).await() ?: return@doInPromise

        courseTitle = title
        Title.update { it.parentPageTitle = title }

        val alreadyEnrolled = CoursesStudentDAO.getMyCourses().await().any { it.id == id }
        if (alreadyEnrolled) {
            EzSpa.PageManager.navigateTo(CourseExercisesPage.link(id))
            error("Do not render")
        }

        joinBtn = ButtonComp(
            ButtonComp.Type.FILLED, Str.doJoin, Icons.check,
            onClick = ::joinCourse,
            disableOnClick = true,
            parent = this
        )
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
                    ${joinBtn.dstIfNotNull()}
                {{/course}}
                {{^course}}
                    <h3>{{invalidHeading}}</h3>
                    <p>{{invalidText}}</p>
                {{/course}}
            </ez-join-course>
        """.trimIndent(),
        "joinStr" to courseTitle?.let { Str.joinCoursePrompt(it) },
        "invalidHeading" to Str.invalidLink,
        "invalidText" to Str.invalidLinkMsg,
        "course" to courseTitle,
    )

    private suspend fun joinCourse() {
        val courseId = if (isMoodleCourse)
            CoursesStudentDAO.joinMoodleCourseByLink(inviteId).await().course_id
        else
            CoursesStudentDAO.joinCourseByLink(inviteId).await().course_id

        EzSpa.PageManager.navigateTo(CourseExercisesPage.link(courseId))
        ToastThing(Str.welcomeToTheCourse)
    }
}