package dao

import debug
import kotlinx.coroutines.await
import org.w3c.fetch.Response
import queries.*
import kotlin.js.Promise

object ExerciseDAO {

    class ExerciseAlreadyOnCourseException : Exception()

    fun addExerciseToCourseQuery(exerciseId: String, courseId: String): Promise<Response> {
        debug { "Adding exercise $exerciseId to course $courseId" }
        return fetchEms("/teacher/courses/$courseId/exercises", ReqMethod.POST,
            mapOf(
                "exercise_id" to exerciseId,
                "threshold" to 100,
                "student_visible" to false,
                "assessments_student_visible" to true,
            ),
            successChecker = { http200 },
            errorHandler = {
                it.handleByCode(RespError.EXERCISE_ALREADY_ON_COURSE) {
                    throw ExerciseAlreadyOnCourseException()
                }
            }
        )
    }

    suspend fun addExerciseToCourse(exerciseId: String, courseId: String): Boolean {
        return try {
            addExerciseToCourseQuery(exerciseId, courseId).await()
            true
        } catch (e: HandledResponseError) {
            if (e.errorHandlerException.await() is ExerciseAlreadyOnCourseException) {
                false
            } else {
                throw e
            }
        }
    }
}