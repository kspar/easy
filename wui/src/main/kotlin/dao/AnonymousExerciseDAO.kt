package dao

import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import queries.ReqMethod
import queries.fetchEms
import queries.http200
import queries.parseTo
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.encodeURIComponent

object AnonymousExerciseDAO {

    @Serializable
    data class AnonExercise(
        val title: String,
        val text_html: String?,
        val anonymous_autoassess_template: String?,
        val submit_allowed: Boolean,
    )

    fun getExerciseDetails(exerciseId: String) = doInPromise {
        fetchEms(
            "/unauth/exercises/${exerciseId.encodeURIComponent()}/anonymous/details", ReqMethod.GET,
            successChecker = { http200 }, withAuth = false
        ).await().parseTo(AnonExercise.serializer()).await()
    }

    @Serializable
    data class Feedback(
        val grade: Int,
        val feedback: String
    )

    fun submit(exerciseId: String, solution: String) = doInPromise {
        fetchEms(
            "/unauth/exercises/${exerciseId.encodeURIComponent()}/anonymous/autoassess", ReqMethod.POST,
            successChecker = { http200 },
            data = mapOf(
                "solution" to solution
            ),
            withAuth = false
        ).await().parseTo(Feedback.serializer()).await()
    }
}