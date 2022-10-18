package pages.courses

import Str
import cache.BasicCourseInfo
import dao.CoursesTeacherDAO
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import rip.kspar.ezspa.CacheableComponent
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import tmRender
import kotlin.js.Promise


class TeacherCoursesRootComp(
    private val isAdmin: Boolean,
    parent: Component?
) : CacheableComponent<TeacherCoursesRootComp.State>(parent) {

    @Serializable
    data class State(val coursesState: TeacherCourseListComp.State)

    private lateinit var coursesList: TeacherCourseListComp

    override val children: List<Component>
        get() = listOf(coursesList)

    override fun create(): Promise<*> = doInPromise {
        coursesList = TeacherCourseListComp(this)
    }

    override fun createFromState(state: State): Promise<*> = doInPromise {
        coursesList = TeacherCourseListComp(this)
    }

    override fun createAndBuildChildrenFromState(state: State): Promise<*> = doInPromise {
        coursesList.createAndBuildFromState(state.coursesState).await()
    }

    override fun render(): String = tmRender(
        "t-c-teach-courses",
        "pageTitle" to if (isAdmin) Str.coursesTitleAdmin() else Str.coursesTitle(),
        "listDstId" to coursesList.dstId
    )

    override fun getCacheableState(): State = State(coursesList.getCacheableState())
}


class TeacherCourseListComp(
    parent: Component?
) : CacheableComponent<TeacherCourseListComp.State>(parent) {

    @Serializable
    data class State(val courses: List<SCourse>)

    @Serializable
    data class SCourse(val id: String, val title: String, val studentCount: Int)


    private var courseItems: List<TeacherCourseItemComp> = emptyList()

    override val children: List<Component>
        get() = courseItems

    override fun create(): Promise<*> = doInPromise {
        courseItems = CoursesTeacherDAO.getMyCourses().map {
            TeacherCourseItemComp(it.id, it.title, it.student_count, this)
        }
    }

    override fun createFromState(state: State): Promise<*> = doInPromise {
        courseItems = state.courses.map { TeacherCourseItemComp(it.id, it.title, it.studentCount, this) }
    }

    override fun render(): String = tmRender("t-c-teach-courses-list",
        "noCoursesLabel" to Str.noCoursesLabel(),
        "courses" to courseItems.map { mapOf("dstId" to it.dstId) }
    )

    override fun renderLoading(): String = tmRender(
        "t-loading-list",
        "items" to listOf(emptyMap<Nothing, Nothing>(), emptyMap(), emptyMap())
    )

    override fun postRender() {
        courseItems.forEach {
            BasicCourseInfo.put(it.id, it.title)
        }
    }

    override fun getCacheableState(): State = State(courseItems.map { SCourse(it.id, it.title, it.studentCount) })
}

class TeacherCourseItemComp(
    val id: String,
    val title: String,
    val studentCount: Int,
    parent: Component?
) : Component(parent) {

    override fun render(): String = tmRender(
        "t-c-teach-courses-item",
        "id" to id,
        "title" to title,
        "count" to studentCount,
        "studentsLabel" to Str.translateStudents(studentCount),
    )
}
