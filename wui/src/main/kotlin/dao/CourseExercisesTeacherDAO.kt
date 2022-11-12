package dao

import DateSerializer
import debug
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import queries.*
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.encodeURIComponent
import kotlin.js.Date
import kotlin.js.Promise

object CourseExercisesTeacherDAO {

    @Serializable
    private data class Exercises(val exercises: List<Exercise>)

    @Serializable
    data class Exercise(
        val id: String,
        val effective_title: String,
        @Serializable(with = DateSerializer::class)
        val soft_deadline: Date?,
        val grader_type: ExerciseDAO.GraderType,
        val ordering_idx: Int,
        val unstarted_count: Int,
        val ungraded_count: Int,
        val started_count: Int,
        val completed_count: Int
    )

    fun getCourseExercises(courseId: String): Promise<List<Exercise>> = doInPromise {
        debug { "Get exercises for teacher course $courseId" }
        fetchEms(
            "/teacher/courses/${courseId.encodeURIComponent()}/exercises", ReqMethod.GET,
            successChecker = { http200 }, errorHandlers = listOf(ErrorHandlers.noCourseAccessPage)
        ).await()
            .parseTo(Exercises.serializer()).await().exercises
            .sortedBy { it.ordering_idx }
    }

    fun removeExerciseFromCourse(courseId: String, courseExerciseId: String): Promise<Unit> = doInPromise {
        fetchEms("/courses/${courseId.encodeURIComponent()}/exercises/${courseExerciseId.encodeURIComponent()}",
            ReqMethod.DELETE,
            successChecker = { http200 }).await()
    }
}