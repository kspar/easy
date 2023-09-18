package dao

import dao.CoursesTeacherDAO.getEffectiveCourseTitle
import debug
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import queries.*
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.encodeURIComponent
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

    @Serializable
    data class JoinedCourse(
        val course_id: String
    )

    fun joinByLink(inviteId: String) = doInPromise {
        debug { "Joining course by invite id $inviteId" }
        fetchEms("/courses/self-add/${inviteId.encodeURIComponent()}", ReqMethod.POST,
            successChecker = { http200 }).await()
            .parseTo(JoinedCourse.serializer()).await()
    }

    @Serializable
    data class CourseInfoByLink(
        val course_id: String,
        val course_title: String,
    )

    fun getCourseTitleByLink(inviteId: String) = doInPromise {
        val resp = try {
            fetchEms("/courses/invite/$inviteId", ReqMethod.GET,
                successChecker = { http200 }, errorHandler = { it.handleByCode(RespError.ENTITY_WITH_ID_NOT_FOUND, {}) }
            ).await()
        } catch (e: HandledResponseError) {
            null
        }

        resp?.parseTo(CourseInfoByLink.serializer())?.await()
    }
}