package cache

import kotlinx.serialization.Serializable
import queries.*
import kotlin.js.Promise


data class CourseInfo(val id: String, val title: String)

object BasicCourseInfo {

    @Serializable
    private data class CourseInfoResp(val title: String)

    private val courseInfoCache = MemoryPromiseCache<String, CourseInfo>("CourseInfo") { courseId ->
        fetchEms(
            "/courses/$courseId/basic", ReqMethod.GET,
            successChecker = { http200 }, errorHandler = ErrorHandlers.noCourseAccessPage
        ).then {
            it.parseTo(CourseInfoResp.serializer())
        }.then {
            CourseInfo(courseId, it.title)
        }
    }

    fun get(courseId: String): Promise<CourseInfo> = courseInfoCache.get(courseId)

    fun put(courseId: String, courseTitle: String) = courseInfoCache.put(courseId, CourseInfo(courseId, courseTitle))
}

