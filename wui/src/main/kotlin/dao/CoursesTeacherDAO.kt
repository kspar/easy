package dao

import Auth
import EzDate
import EzDateSerializer
import Role
import debug
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import queries.*
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.encodeURIComponent
import kotlin.js.Promise

object CoursesTeacherDAO {

    @Serializable
    private data class Courses(val courses: List<Course>)

    @Serializable
    data class Course(
        val id: String,
        val title: String,
        val alias: String?,
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


    fun updateCourse(courseId: String, title: String, alias: String?) = doInPromise {
        debug { "Updating course $courseId title to $title and alias to $alias" }

        fetchEms("/courses/${courseId.encodeURIComponent()}", ReqMethod.PUT,
            mapOf(
                "title" to title,
                "alias" to alias,
            ),
            successChecker = { http200 }).await()
    }


    fun getEffectiveCourseTitle(title: String, alias: String?): String =
        if (alias == null || Auth.activeRole == Role.ADMIN) title else alias


    @Serializable
    data class NewLink(
        val invite_id: String
    )

    fun createJoinLink(courseId: String, expiresAt: EzDate, allowedUses: Int) = doInPromise {
        debug { "Creating/updating join link for course $courseId to time: $expiresAt and allowed uses: $allowedUses" }

        fetchEms("/courses/${courseId.encodeURIComponent()}/invite", ReqMethod.PUT,
            mapOf(
                "expires_at" to expiresAt.date,
                "allowed_uses" to allowedUses,
            ),
            successChecker = { http200 }
        ).await()
            .parseTo(NewLink.serializer()).await()
    }

    fun deleteJoinLink(courseId: String) = doInPromise {
        debug { "Deleting join link for course $courseId" }

        fetchEms("/courses/${courseId.encodeURIComponent()}/invite", ReqMethod.DELETE,
            successChecker = { http200 }).await()
    }

    @Serializable
    data class ExistingLink(
        val invite_id: String,
        @Serializable(with = EzDateSerializer::class)
        val expires_at: EzDate,
        @Serializable(with = EzDateSerializer::class)
        val created_at: EzDate,
        val allowed_uses: Int,
        val used_count: Int,
        val uses_remaining: Int,
    )

    fun getJoinLink(courseId: String) = doInPromise {
        debug { "Getting course join link details for course $courseId" }
        fetchEms(
            "/courses/${courseId.encodeURIComponent()}/invite",
            ReqMethod.GET,
            successChecker = { http200 }).await()
            .parseToOrNull(ExistingLink.serializer()).await()
    }

}