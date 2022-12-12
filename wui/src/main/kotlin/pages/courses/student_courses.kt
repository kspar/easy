package pages.courses

import Str
import dao.CoursesTeacherDAO
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import queries.ReqMethod
import queries.fetchEms
import queries.http200
import queries.parseTo
import rip.kspar.ezspa.CacheableComponent
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import tmRender
import kotlin.js.Promise


class StudentCoursesRootComp(
    parent: Component?
) : CacheableComponent<StudentCoursesRootComp.State>(parent) {

    @Serializable
    data class State(val coursesState: StudentCourseListComp.State)

    private lateinit var coursesList: StudentCourseListComp

    override val children: List<Component>
        get() = listOf(coursesList)

    override fun create(): Promise<*> = doInPromise {
        coursesList = StudentCourseListComp(this)
    }

    override fun createFromState(state: State): Promise<*> = doInPromise {
        coursesList = StudentCourseListComp(this)
    }

    override fun createAndBuildChildrenFromState(state: State): Promise<*> = doInPromise {
        coursesList.createAndBuildFromState(state.coursesState).await()
    }

    override fun render(): String = tmRender(
        "t-c-stud-courses",
        "pageTitle" to Str.coursesTitle(),
        "listDstId" to coursesList.dstId
    )

    override fun getCacheableState(): State = State(coursesList.getCacheableState())
}


class StudentCourseListComp(
    parent: Component?
) : CacheableComponent<StudentCourseListComp.State>(parent) {

    @Serializable
    data class State(val courses: List<SCourse>)

    @Serializable
    data class SCourse(val id: String, val title: String)

    @Serializable
    data class CoursesDto(val courses: List<CourseDto>)

    @Serializable
    data class CourseDto(val id: String, private val title: String, private val alias: String?) {
        val effectiveTitle: String
            get() = CoursesTeacherDAO.getEffectiveCourseTitle(title, alias)
    }


    private var courseItems: List<StudentCourseItemComp> = emptyList()

    override val children: List<Component>
        get() = courseItems

    override fun create(): Promise<*> = doInPromise {
        courseItems = fetchEms("/student/courses", ReqMethod.GET, successChecker = { http200 }).await()
            .parseTo(CoursesDto.serializer()).await()
            .courses
            // Temp hack to sort by created time - newer on top
            .sortedByDescending { it.id.toInt() }
            .map { StudentCourseItemComp(it.id, it.effectiveTitle, this) }
    }

    override fun createFromState(state: State): Promise<*> = doInPromise {
        courseItems = state.courses.map { StudentCourseItemComp(it.id, it.title, this) }
    }

    override fun render(): String = tmRender("t-c-stud-courses-list",
        "noCoursesLabel" to Str.noCoursesLabel(),
        "courses" to courseItems.map { mapOf("dstId" to it.dstId) }
    )

    override fun renderLoading(): String = tmRender(
        "t-loading-list",
        "items" to listOf(emptyMap<Nothing, Nothing>(), emptyMap(), emptyMap())
    )

    override fun getCacheableState(): State = State(courseItems.map { SCourse(it.id, it.title) })
}


class StudentCourseItemComp(
    val id: String,
    val title: String,
    parent: Component?
) : Component(parent) {

    override fun render(): String = tmRender(
        "t-c-stud-courses-item",
        "id" to id,
        "title" to title
    )
}
