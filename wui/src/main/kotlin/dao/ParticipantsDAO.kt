package dao

import debug
import kotlinx.coroutines.await
import queries.ReqMethod
import queries.fetchEms
import queries.http200
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.encodeURIComponent

object ParticipantsDAO {

    fun sendStudentCourseInvites(courseId: String, emails: List<String>) = doInPromise {
        debug { "Sending course invites to students $emails" }
        fetchEms("/courses/${courseId.encodeURIComponent()}/students/invite", ReqMethod.POST,
            mapOf("emails" to emails.map { mapOf("email" to it) }),
            successChecker = { http200 }).await()
        Unit
    }
}