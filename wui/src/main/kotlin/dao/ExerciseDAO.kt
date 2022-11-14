package dao

import blankToNull
import debug
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import queries.*
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.encodeURIComponent
import kotlin.js.Promise

object ExerciseDAO {

    data class UpdatedExercise(
        val title: String,
        val textAdoc: String,
        val textHtml: String,
        val autoeval: Autoeval?,
    )

    data class Autoeval(
        val containerImage: String,
        val evalScript: String,
        val assets: Map<String, String>,
        val maxTime: Int,
        val maxMem: Int,
    )

    enum class GraderType {
        AUTO, TEACHER
    }

    fun updateExercise(exerciseId: String, exercise: UpdatedExercise): Promise<Unit> = doInPromise {
        debug { "Updating exercise ${exercise.title} ($exerciseId)" }

        val body = exercise.let {
            mapOf(
                "title" to it.title,
                // avoid overwriting html with empty adoc for html-only exercises
                "text_adoc" to it.textAdoc.blankToNull(),
                "text_html" to it.textHtml,
                // TODO: remove
                "public" to false,
                "grader_type" to if (it.autoeval != null) GraderType.AUTO.name else GraderType.TEACHER.name,
                "container_image" to it.autoeval?.containerImage,
                "grading_script" to it.autoeval?.evalScript,
                "max_time_sec" to it.autoeval?.maxTime,
                "max_mem_mb" to it.autoeval?.maxMem,
                "assets" to it.autoeval?.assets?.map { mapOf("file_name" to it.key, "file_content" to it.value) },
                "anonymous_autoassess_enabled" to false,
                "anonymous_autoassess_template" to null,
            )
        }

        fetchEms("/exercises/${exerciseId.encodeURIComponent()}", ReqMethod.PUT, body,
            successChecker = { http200 }).await()
    }

    @Serializable
    data class CourseExerciseId(val id: String)

    private class ExerciseAlreadyOnCourseException : Exception()

    fun addExerciseToCourse(exerciseId: String, courseId: String): Promise<String?> = doInPromise {
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
            ).await().parseTo(CourseExerciseId.serializer()).await().id
        } catch (e: HandledResponseError) {
            if (e.errorHandlerException.await() is ExerciseAlreadyOnCourseException) {
                null
            } else {
                throw e
            }
        }
    }
}