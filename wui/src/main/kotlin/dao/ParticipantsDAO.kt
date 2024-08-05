package dao

import EzDate
import EzDateSerializer
import HumanStringComparator
import debug
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import queries.*
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.encodeURIComponent

object ParticipantsDAO {

    @Serializable
    data class Participants(
        val students: List<Student> = emptyList(),
        val teachers: List<Teacher> = emptyList(),
        val students_pending: List<PendingStudent> = emptyList(),
        val students_moodle_pending: List<PendingMoodleStudent> = emptyList()
    )

    @Serializable
    data class Teacher(
        val id: String,
        val email: String,
        val given_name: String,
        val family_name: String,
        @Serializable(with = EzDateSerializer::class)
        val created_at: EzDate?
    )

    @Serializable
    data class Student(
        val id: String,
        val email: String,
        val given_name: String,
        val family_name: String,
        val groups: List<CourseGroup>,
        val moodle_username: String? = null,
        @Serializable(with = EzDateSerializer::class)
        val created_at: EzDate?
    )

    @Serializable
    data class PendingStudent(
        val email: String,
        @Serializable(with = EzDateSerializer::class)
        val valid_from: EzDate,
        val groups: List<CourseGroup>
    )

    @Serializable
    data class PendingMoodleStudent(
        val moodle_username: String,
        val email: String,
        val invite_id: String,
        val groups: List<CourseGroup>
    )

    @Serializable
    data class CourseGroup(
        val id: String,
        val name: String
    )

    @Serializable
    data class Groups(
        val groups: List<CourseGroup>,
    )

    @Serializable
    data class MoodleStatus(
        val moodle_props: MoodleSettings?
    )

    @Serializable
    data class MoodleSettings(
        val moodle_short_name: String,
        val students_synced: Boolean,
        val grades_synced: Boolean,
        val sync_students_in_progress: Boolean,
        val sync_grades_in_progress: Boolean,
    )

    fun getCourseParticipants(courseId: String) = doInPromise {
        debug { "Get participants on course $courseId" }
        fetchEms(
            "/courses/${courseId.encodeURIComponent()}/participants", ReqMethod.GET,
            successChecker = { http200 }, errorHandler = ErrorHandlers.noCourseAccessMsg
        ).await().parseTo(Participants.serializer()).await()
    }

    fun getCourseGroups(courseId: String) = doInPromise {
        debug { "Get participants on course $courseId" }
        fetchEms(
            "/courses/$courseId/groups", ReqMethod.GET,
            successChecker = { http200 }, errorHandler = ErrorHandlers.noCourseAccessMsg
        ).await().parseTo(Groups.serializer()).await().groups.sortedWith(compareBy(HumanStringComparator) { it.name })
    }

    fun getCourseMoodleSettings(courseId: String) = doInPromise {
        debug { "Get participants on course $courseId" }
        fetchEms(
            "/courses/$courseId/moodle", ReqMethod.GET,
            successChecker = { http200 }, errorHandler = ErrorHandlers.noCourseAccessMsg
        ).await().parseTo(MoodleStatus.serializer()).await()
    }


    fun sendStudentCourseInvites(courseId: String, emails: List<String>) = doInPromise {
        debug { "Sending course invites to students $emails" }
        fetchEms("/courses/${courseId.encodeURIComponent()}/students/invite", ReqMethod.POST,
            mapOf("emails" to emails.map { mapOf("email" to it) }),
            successChecker = { http200 }).await()
        Unit
    }

    fun sendStudentMoodleCourseInvites(courseId: String, moodleIds: List<String>) = doInPromise {
        debug { "Sending moodle course invites to students $moodleIds" }
        fetchEms("/courses/moodle/${courseId.encodeURIComponent()}/students/invite", ReqMethod.POST,
            mapOf("students" to moodleIds),
            successChecker = { http200 }).await()
        Unit
    }
}