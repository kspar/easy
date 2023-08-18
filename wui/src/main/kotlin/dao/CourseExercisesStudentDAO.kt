package dao

import EzDate
import EzDateSerializer
import Icons
import debug
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import queries.*
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.encodeURIComponent
import kotlin.js.Promise

object CourseExercisesStudentDAO {

    @Serializable
    private data class Exercises(val exercises: List<Exercise>)

    enum class SubmissionStatus { UNSTARTED, STARTED, COMPLETED, UNGRADED }

    @Serializable
    data class Exercise(
        val id: String,
        val effective_title: String,
        val grader_type: ExerciseDAO.GraderType,
        @Serializable(with = EzDateSerializer::class)
        val deadline: EzDate?,
        val status: SubmissionStatus,
        val grade: Int?,
        val graded_by: ExerciseDAO.GraderType?,
        val ordering_idx: Int,
    ) {
        val icon = if (grader_type == ExerciseDAO.GraderType.AUTO) Icons.robot else Icons.teacherFace
    }

    fun getCourseExercises(courseId: String): Promise<List<Exercise>> = doInPromise {
        debug { "Get exercises for student course $courseId" }
        fetchEms(
            "/student/courses/${courseId.encodeURIComponent()}/exercises", ReqMethod.GET,
            successChecker = { http200 }, errorHandlers = listOf(ErrorHandlers.noCourseAccessMsg)
        ).await()
            .parseTo(Exercises.serializer()).await().exercises
            .sortedBy { it.ordering_idx }
    }
}