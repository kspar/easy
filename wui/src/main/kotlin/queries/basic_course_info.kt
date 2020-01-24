package queries

import CourseInfoCache
import debug
import kotlinx.serialization.Serializable
import kotlin.js.Promise


data class CourseInfo(val id: String, val title: String)

object BasicCourseInfo {

    @Serializable
    data class CourseInfoResp(val title: String)

    fun get(courseId: String): Promise<CourseInfo> {
        val fromCache = CourseInfoCache[courseId]
        if (fromCache != null) {
            debug { "Course info cache hit for course $courseId" }
            return Promise.Companion.resolve(fromCache)
        }

        debug { "Course info cache miss for course $courseId" }
        return fetchEms("/courses/$courseId/basic", ReqMethod.GET, successChecker = { http200 })
                .then {
                    it.parseTo(CourseInfoResp.serializer())
                }.then {
                    val courseInfo = CourseInfo(courseId, it.title)
                    CourseInfoCache[courseId] = courseInfo
                    courseInfo
                }
    }
}
