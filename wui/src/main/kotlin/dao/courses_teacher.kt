package dao

import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import org.w3c.fetch.Response
import queries.ReqMethod
import queries.fetchEms
import queries.http200
import queries.parseTo
import kotlin.js.Promise

object CoursesTeacher {

    @Serializable
    data class Courses(val courses: List<Course>)

    @Serializable
    data class Course(val id: String, val title: String, val student_count: Int)

    fun getMyCoursesQuery(): Promise<Response> {
        return fetchEms("/teacher/courses", ReqMethod.GET, successChecker = { http200 })
    }

    suspend fun getMyCourses(): List<Course> {
        return getMyCoursesQuery().await()
            .parseTo(Courses.serializer()).await()
            .courses
            // Temp hack to sort by created time - newer on top
            .sortedByDescending { it.id.toInt() }
    }
}