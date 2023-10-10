package pages.courses

import Icons
import components.EzCollComp
import dao.CoursesTeacherDAO
import debug
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import pages.course_exercises_list.CourseExercisesPage
import pages.participants.ParticipantsPage
import queries.getCurrentQueryParamValue
import rip.kspar.ezspa.CacheableComponent
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.EzSpa
import rip.kspar.ezspa.doInPromise
import template
import translation.Str
import kotlin.js.Promise


class TeacherCoursesComp(
    private val isAdmin: Boolean,
    parent: Component?
) : CacheableComponent<TeacherCoursesComp.State>(parent) {

    @Serializable
    data class State(val courses: List<SCourse>)

    @Serializable
    data class SCourse(val id: String, val title: String, val alias: String?, val studentCount: Int)


    data class CourseProps(
        val id: String,
        val title: String,
    )

    private lateinit var courses: List<CoursesTeacherDAO.Course>
    private lateinit var coll: EzCollComp<CourseProps>

    override val children: List<Component>
        get() = listOf(coll)

    override fun create(): Promise<*> = doInPromise {
        courses = CoursesTeacherDAO.getMyCourses().await()
        if (getCurrentQueryParamValue(CoursesPage.REDIR_ALLOWED_PARAM) != null && courses.size == 1) {
            debug { "Redirecting to exercises" }
            EzSpa.PageManager.navigateTo(CourseExercisesPage.link(courses[0].id))
        } else {
            CoursesPage.updateUrl(CoursesPage.link())
            createColl()
        }
    }

    override fun createFromState(state: State): Promise<*> = doInPromise {
        courses = state.courses.map { CoursesTeacherDAO.Course(it.id, it.title, it.alias, it.studentCount) }
        createColl()
    }

    private fun createColl() {
        coll = EzCollComp(
            courses.map { course ->
                EzCollComp.Item(
                    CourseProps(course.id, course.effectiveTitle),
                    EzCollComp.ItemTypeIcon(Icons.articles),
                    course.effectiveTitle,
                    titleInteraction = EzCollComp.TitleLink(CourseExercisesPage.link(course.id)),
                    topAttr = EzCollComp.SimpleAttr(
                        Str.enrolledOnCourseAttrKey,
                        "${course.student_count} ${Str.translateStudents(course.student_count)}",
                        Icons.user,
                        onClick = { EzSpa.PageManager.navigateTo(ParticipantsPage.link(course.id)); EzCollComp.ResultUnmodified }
                    )
                )
            },
            EzCollComp.Strings(Str.coursesSingular, Str.coursesPlural),
            parent = this
        )
    }

    override fun render(): String = template(
        """
            <div class="title-wrap no-crumb">
                <h2 class="title">{{title}}</h2>
            </div>
            <ez-dst id="{{collDst}}"></ez-dst>
        """.trimIndent(),
        "title" to if (isAdmin) Str.coursesTitleAdmin else Str.coursesTitle,
        "collDst" to coll.dstId,
    )

    override fun getCacheableState(): State =
        State(courses.map { SCourse(it.id, it.title, it.alias, it.student_count) })
}