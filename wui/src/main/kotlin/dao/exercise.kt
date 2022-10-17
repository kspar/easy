package dao

import debug
import kotlinx.coroutines.await
import org.w3c.fetch.Response
import queries.ReqMethod
import queries.fetchEms
import queries.http200
import kotlin.js.Promise

object ExerciseDAO {

    fun addExerciseToCourseQuery(exerciseId: String, courseId: String): Promise<Response> {
        debug { "Adding exercise $exerciseId to course $courseId" }
        return fetchEms("/teacher/courses/$courseId/exercises", ReqMethod.POST,
            mapOf(
                "exercise_id" to exerciseId,
                "threshold" to 100,
                "student_visible" to false,
                "assessments_student_visible" to true,
            ),
            successChecker = { http200 }
        )
    }

    suspend fun addExerciseToCourse(exerciseId: String, courseId: String) {
        addExerciseToCourseQuery(exerciseId, courseId).await()
    }
}