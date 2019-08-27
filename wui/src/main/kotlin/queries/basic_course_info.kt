package queries

import CourseInfoCache
import ReqMethod
import Str
import debug
import errorMessage
import fetchEms
import http200
import kotlinx.serialization.Serializable
import parseTo
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
        return fetchEms("/courses/$courseId/basic", ReqMethod.GET)
                .then {
                    if (!it.http200) {
                        errorMessage { Str.somethingWentWrong() }
                        error("Fetching course info failed with status ${it.status}")
                    }
                    it.parseTo(CourseInfoResp.serializer())
                }.then {
                    val courseInfo = CourseInfo(courseId, it.title)
                    CourseInfoCache[courseId] = courseInfo
                    courseInfo
                }
    }
}
