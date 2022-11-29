package dao

import Auth
import Role
import debug
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import queries.ReqMethod
import queries.fetchEms
import queries.http200
import queries.parseTo
import rip.kspar.ezspa.doInPromise
import kotlin.js.Promise

object CoursesTeacherDAO {

    @Serializable
    private data class Courses(val courses: List<Course>)

    @Serializable
    data class Course(
        val id: String,
        private val title: String,
        private val alias: String?,
        val student_count: Int
    ) {
        val effectiveTitle: String
            get() = getEffectiveCourseTitle(title, alias)
    }

    fun getMyCourses(): Promise<List<Course>> = doInPromise {
        debug { "Getting my courses teacher" }
        fetchEms("/teacher/courses", ReqMethod.GET, successChecker = { http200 }).await()
            .parseTo(Courses.serializer()).await()
            .courses
            // Temp hack to sort by created time - newer on top
            .sortedByDescending { it.id.toInt() }
    }

    fun getEffectiveCourseTitle(title: String, alias: String?): String =
        if (alias == null || Auth.activeRole == Role.ADMIN) title else alias
}