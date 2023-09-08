package pages.courses

import Icons
import components.EzCollComp
import dao.CoursesStudentDAO
import debug
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import pages.course_exercises_list.CourseExercisesPage
import queries.getCurrentQueryParamValue
import rip.kspar.ezspa.CacheableComponent
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.EzSpa
import rip.kspar.ezspa.doInPromise
import template
import translation.Str
import kotlin.js.Promise


class StudentCoursesComp(
    parent: Component?
) : CacheableComponent<StudentCoursesComp.State>(parent) {

    @Serializable
    data class State(val courses: List<SCourse>)

    @Serializable
    data class SCourse(val id: String, val title: String, val alias: String?)

    data class CourseProps(
        val id: String,
        val title: String,
    )

    private lateinit var courses: List<CoursesStudentDAO.Course>
    private lateinit var coll: EzCollComp<CourseProps>

    override val children: List<Component>
        get() = listOf(coll)

    override fun create(): Promise<*> = doInPromise {
        courses = CoursesStudentDAO.getMyCourses().await()
        if (getCurrentQueryParamValue(CoursesPage.REDIR_ALLOWED_PARAM) != null && courses.size == 1) {
            debug { "Redirecting to exercises" }
            EzSpa.PageManager.navigateTo(CourseExercisesPage.link(courses[0].id))
        } else {
            CoursesPage.updateUrl(CoursesPage.link())
            createColl()
        }
    }

    override fun createFromState(state: State): Promise<*> = doInPromise {
        courses = state.courses.map { CoursesStudentDAO.Course(it.id, it.title, it.alias) }
        createColl()
    }

    private fun createColl() {
        coll = EzCollComp(
            courses.map {
                EzCollComp.Item(
                    CourseProps(it.id, it.effectiveTitle),
                    EzCollComp.ItemTypeIcon(Icons.articles),
                    it.effectiveTitle,
                    titleAction = { EzSpa.PageManager.navigateTo(CourseExercisesPage.link(it.id)) },
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
        "title" to Str.coursesTitle,
        "collDst" to coll.dstId
    )

    override fun getCacheableState(): State = State(courses.map { SCourse(it.id, it.title, it.alias) })
}