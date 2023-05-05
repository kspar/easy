package dao

import EzDate
import EzDateSerializer
import debug
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import queries.*
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.encodeURIComponent
import kotlin.js.Promise

object CourseExercisesTeacherDAO {

    @Serializable
    private data class Exercises(val exercises: List<Exercise>)

    @Serializable
    data class Exercise(
        val id: String,
        val library_title: String,
        val title_alias: String? = null,
        @Serializable(with = EzDateSerializer::class)
        val student_visible_from: EzDate?,
        @Serializable(with = EzDateSerializer::class)
        val soft_deadline: EzDate?,
        val grader_type: ExerciseDAO.GraderType,
        val ordering_idx: Int,
        val unstarted_count: Int,
        val ungraded_count: Int,
        val started_count: Int,
        val completed_count: Int
    ) {
        val effectiveTitle = title_alias ?: library_title
        val isVisibleNow
            get() = student_visible_from != null && EzDate.now() >= student_visible_from
    }

    fun getCourseExercises(courseId: String): Promise<List<Exercise>> = doInPromise {
        debug { "Get exercises for teacher course $courseId" }
        fetchEms(
            "/teacher/courses/${courseId.encodeURIComponent()}/exercises", ReqMethod.GET,
            successChecker = { http200 }, errorHandlers = listOf(ErrorHandlers.noCourseAccessPage)
        ).await()
            .parseTo(Exercises.serializer()).await().exercises
            .sortedBy { it.ordering_idx }
    }

    fun removeExerciseFromCourse(courseId: String, courseExerciseId: String) = doInPromise {
        debug { "Remove course $courseId exercise $courseExerciseId" }
        fetchEms("/courses/${courseId.encodeURIComponent()}/exercises/${courseExerciseId.encodeURIComponent()}",
            ReqMethod.DELETE,
            successChecker = { http200 }).await()
        Unit
    }

    fun reorderCourseExercise(courseId: String, courseExerciseId: String, newIdx: Int) = doInPromise {
        debug { "Reorder course $courseId exercise $courseExerciseId to idx $newIdx" }
        fetchEms("/courses/${courseId.encodeURIComponent()}/exercises/${courseExerciseId.encodeURIComponent()}/reorder",
            ReqMethod.POST,
            mapOf("new_index" to newIdx),
            successChecker = { http200 }).await()
        Unit
    }


    data class CourseExerciseUpdate(
        val replace: CourseExerciseReplace? = null,
        val delete: Set<CourseExerciseDelete> = emptySet(),
    )

    data class CourseExerciseReplace(
        val titleAlias: String? = null,
        val instructions: String? = null,
        val threshold: Int? = null,
        val softDeadline: EzDate? = null,
        val hardDeadline: EzDate? = null,
        val isStudentVisible: Boolean? = null,
        val studentVisibleFrom: EzDate? = null,
        val assessmentsStudentVisible: Boolean? = null,
        val moodleExerciseId: String? = null,
    )

    enum class CourseExerciseDelete {
        TITLE_ALIAS,
        INSTRUCTIONS_ADOC,
        SOFT_DEADLINE,
        HARD_DEADLINE,
        MOODLE_EXERCISE_ID,
    }

    fun updateCourseExercise(courseId: String, courseExerciseId: String, update: CourseExerciseUpdate) = doInPromise {
        debug { "Update course $courseId exercise $courseExerciseId with $update" }
        val replaceField = update.replace?.let {
            mapOf(
                "title_alias" to it.titleAlias,
                "instructions_adoc" to it.instructions,
                "threshold" to it.threshold,
                "soft_deadline" to it.softDeadline?.date,
                "hard_deadline" to it.hardDeadline?.date,
                "assessments_student_visible" to it.assessmentsStudentVisible,
                "student_visible" to it.isStudentVisible,
                "student_visible_from" to it.studentVisibleFrom?.toIsoString(),
                "moodle_exercise_id" to it.moodleExerciseId,
            )
        }

        fetchEms("/courses/${courseId.encodeURIComponent()}/exercises/${courseExerciseId.encodeURIComponent()}",
            ReqMethod.PATCH,
            mapOf(
                "replace" to replaceField,
                "delete" to update.delete.toList()
            ),
            successChecker = { http200 }).await()

        Unit
    }
}