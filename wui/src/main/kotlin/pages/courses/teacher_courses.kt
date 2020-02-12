package pages.courses

import CourseInfoCache
import IdGenerator
import Str
import doInPromise
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import queries.*
import spa.CacheableComponent
import spa.Component
import tmRender
import kotlin.js.Promise


class TeacherCoursesRootComp(
        private val isAdmin: Boolean,
        parent: Component?,
        dstId: String = IdGenerator.nextId()
) : CacheableComponent<TeacherCoursesRootComp.State>(dstId, parent) {

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

    override fun render(): String = tmRender("t-c-teach-courses",
            "pageTitle" to if (isAdmin) Str.coursesTitleAdmin() else Str.coursesTitle(),
            "canAddCourse" to isAdmin,
            "newCourseLabel" to Str.newCourseLink(),
            "listDstId" to coursesList.dstId
    )

    override fun getCacheableState(): State = State(coursesList.getCacheableState())
}


class TeacherCourseListComp(
        parent: Component?,
        dstId: String = IdGenerator.nextId()
) : CacheableComponent<TeacherCourseListComp.State>(dstId, parent) {

    @Serializable
    data class State(val courses: List<SCourse>)

    @Serializable
    data class SCourse(val id: String, val title: String, val studentCount: Int)

    @Serializable
    data class CoursesDto(val courses: List<CourseDto>)

    @Serializable
    data class CourseDto(val id: String, val title: String, val student_count: Int)


    private var courseItems: List<TeacherCourseItemComp> = emptyList()

    override val children: List<Component>
        get() = courseItems

    override fun create(): Promise<*> = doInPromise {
        courseItems = fetchEms("/teacher/courses", ReqMethod.GET, successChecker = { http200 }).await()
                .parseTo(CoursesDto.serializer()).await()
                .courses.map { TeacherCourseItemComp(it.id, it.title, it.student_count, this) }
    }

    override fun createFromState(state: State): Promise<*> = doInPromise {
        courseItems = state.courses.map { TeacherCourseItemComp(it.id, it.title, it.studentCount, this) }
    }

    override fun render(): String = tmRender("t-c-teach-courses-list",
            "noCoursesLabel" to Str.noCoursesLabel(),
            "courses" to courseItems.map { mapOf("dstId" to it.dstId) }
    )

    override fun renderLoading(): String = tmRender("t-loading-list",
            "items" to listOf(emptyMap<Nothing, Nothing>(), emptyMap(), emptyMap())
    )

    override fun postRender() {
        courseItems.forEach {
            CourseInfoCache[it.id] = CourseInfo(it.id, it.title)
        }
    }

    override fun getCacheableState(): State = State(courseItems.map { SCourse(it.id, it.title, it.studentCount) })
}

class TeacherCourseItemComp(
        val id: String,
        val title: String,
        val studentCount: Int,
        parent: Component?,
        dstId: String = IdGenerator.nextId()
) : Component(dstId, parent) {

    override fun render(): String = tmRender("t-c-teach-courses-item",
            "id" to id,
            "title" to title,
            "count" to studentCount,
            "studentsLabel" to if (studentCount == 1) Str.coursesStudent() else Str.coursesStudents()
    )
}
