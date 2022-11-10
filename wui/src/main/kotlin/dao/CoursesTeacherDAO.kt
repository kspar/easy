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

object CoursesTeacherDAO {

    @Serializable
    private data class Courses(val courses: List<Course>)

    @Serializable
    data class Course(val id: String, val title: String, val student_count: Int)

    fun getMyCourses(): Promise<List<Course>> = doInPromise {
        debug { "Getting my courses teacher" }
        fetchEms("/teacher/courses", ReqMethod.GET, successChecker = { http200 }).await()
            .parseTo(Courses.serializer()).await()
            .courses
            // Temp hack to sort by created time - newer on top
            .sortedByDescending { it.id.toInt() }
    }


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
}