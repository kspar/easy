package cache

import dao.CoursesTeacherDAO
import kotlinx.serialization.Serializable
import queries.*
import kotlin.js.Promise


data class CourseInfo(val id: String, val title: String, val alias: String?) {
    val effectiveTitle: String
        get() = CoursesTeacherDAO.getEffectiveCourseTitle(title, alias)
}

object BasicCourseInfo {

    @Serializable
    private data class CourseInfoResp(val title: String, val alias: String?)

    private val courseInfoCache = MemoryPromiseCache<String, CourseInfo>("CourseInfo") { courseId ->
        fetchEms(
            "/courses/$courseId/basic", ReqMethod.GET,
            successChecker = { http200 }, errorHandler = ErrorHandlers.noCourseAccessPage
        ).then {
            it.parseTo(CourseInfoResp.serializer())
        }.then {
            CourseInfo(courseId, it.title, it.alias)
        }
    }

    fun get(courseId: String): Promise<CourseInfo> = courseInfoCache.get(courseId)

    fun invalidate(courseId: String) = courseInfoCache.invalidate(courseId)
}
