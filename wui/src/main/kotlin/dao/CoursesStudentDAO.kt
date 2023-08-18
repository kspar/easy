package dao

import dao.CoursesTeacherDAO.getEffectiveCourseTitle
import debug
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import queries.ReqMethod
import queries.fetchEms
import queries.http200
import queries.parseTo
import rip.kspar.ezspa.doInPromise
import kotlin.js.Promise

object CoursesStudentDAO {

    @Serializable
    private data class Courses(val courses: List<Course>)

    @Serializable
    data class Course(
        val id: String,
        val title: String,
        val alias: String?,
    ) {
        val effectiveTitle: String
            get() = getEffectiveCourseTitle(title, alias)
    }

    fun getMyCourses(): Promise<List<Course>> = doInPromise {
        debug { "Getting my courses student" }
        fetchEms("/student/courses", ReqMethod.GET, successChecker = { http200 }).await()
            .parseTo(Courses.serializer()).await()
            .courses
            // Temp hack to sort by created time - newer on top
            .sortedByDescending { it.id.toInt() }
    }
}