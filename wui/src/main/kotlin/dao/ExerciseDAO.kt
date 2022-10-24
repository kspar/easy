package dao

import debug
import kotlinx.coroutines.await
import queries.*
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.encodeURIComponent
import kotlin.js.Promise

object ExerciseDAO {

    private class ExerciseAlreadyOnCourseException : Exception()

    fun addExerciseToCourse(exerciseId: String, courseId: String): Promise<Boolean> = doInPromise {
        debug { "Adding exercise $exerciseId to course $courseId" }
        try {
            fetchEms("/teacher/courses/${courseId.encodeURIComponent()}/exercises", ReqMethod.POST,
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
            ).await()
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