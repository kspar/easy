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


class StudentCourseListComp(dstId: String
) : CacheableComponent<StudentCourseListComp.State>(dstId) {

    @Serializable
    data class State(val courses: List<SCourse>)

    @Serializable
    data class SCourse(val id: String, val title: String)

    @Serializable
    data class CoursesDto(val courses: List<CourseDto>)

    @Serializable
    data class CourseDto(val id: String, val title: String)


    private var courseItems: List<StudentCourseItemComp> = emptyList()

    override val children: List<Component>
        get() = courseItems

    override fun create(): Promise<*> = doInPromise {
        courseItems = fetchEms("/student/courses", ReqMethod.GET, successChecker = { http200 }).await()
                .parseTo(CoursesDto.serializer()).await()
                .courses.map { StudentCourseItemComp(IdGenerator.nextId(), it.id, it.title) }
    }

    override fun createFromState(state: State): Promise<*> = doInPromise {
        courseItems = state.courses.map { StudentCourseItemComp(IdGenerator.nextId(), it.id, it.title) }
    }

    override fun render(): String = tmRender("t-c-stud-courses-list",
            "pageTitle" to Str.coursesTitle(),
            "noCoursesLabel" to Str.noCoursesLabel(),
            "courses" to courseItems.map { mapOf("dstId" to it.dstId) }
    )

    override fun init() {
        courseItems.forEach {
            CourseInfoCache[it.id] = CourseInfo(it.id, it.title)
        }
    }

    override fun getCacheableState(): State = State(courseItems.map { SCourse(it.id, it.title) })
}


class StudentCourseItemComp(dstId: String,
                            val id: String,
                            val title: String
) : Component(dstId) {

    override fun render(): String = tmRender("t-c-stud-courses-item",
            "id" to id,
            "title" to title
    )
}

