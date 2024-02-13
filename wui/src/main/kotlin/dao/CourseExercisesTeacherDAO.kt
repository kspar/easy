package dao

import DateSerializer
import EzDate
import EzDateSerializer
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
        val exercise_id: String,
        val library_title: String,
        val title_alias: String? = null,
        @Serializable(with = EzDateSerializer::class)
        val student_visible_from: EzDate?,
        @Serializable(with = EzDateSerializer::class)
        val soft_deadline: EzDate?,
        @Serializable(with = EzDateSerializer::class)
        val hard_deadline: EzDate?,
        val grader_type: ExerciseDAO.GraderType,
        val threshold: Int,
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
            successChecker = { http200 }, errorHandlers = listOf(ErrorHandlers.noCourseAccessMsg)
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


    @Serializable
    data class TeacherCourseExerciseDetails(
        val exercise_id: String,
        val title: String,
        val title_alias: String?,
        val instructions_html: String?,
        val instructions_adoc: String?,
        val text_html: String?,
        val text_adoc: String?,
        val student_visible: Boolean,
        @Serializable(with = DateSerializer::class)
        val student_visible_from: Date?,
        @Serializable(with = DateSerializer::class)
        val hard_deadline: Date?,
        @Serializable(with = DateSerializer::class)
        val soft_deadline: Date?,
        val grader_type: ExerciseDAO.GraderType,
        val solution_file_name: String,
        val solution_file_type: ExerciseDAO.SolutionFileType,
        val threshold: Int,
        @Serializable(with = DateSerializer::class)
        val last_modified: Date,
        val assessments_student_visible: Boolean,
        val grading_script: String?,
        val container_image: String?,
        val max_time_sec: Int?,
        val max_mem_mb: Int?,
        val assets: List<AutoAsset>?,
        val executors: List<AutoExecutor>?,
        val has_lib_access: Boolean,
    ) {
        val effectiveTitle = title_alias ?: title
    }

    @Serializable
    data class AutoAsset(
        val file_name: String,
        val file_content: String
    )

    @Serializable
    data class AutoExecutor(
        val id: String,
        val name: String
    )

    fun getCourseExerciseDetails(courseId: String, courseExerciseId: String) = doInPromise {
        fetchEms(
            "/teacher/courses/${courseId.encodeURIComponent()}/exercises/${courseExerciseId.encodeURIComponent()}",
            ReqMethod.GET,
            successChecker = { http200 },
            errorHandler = ErrorHandlers.noCourseAccessMsg
        ).await().parseTo(TeacherCourseExerciseDetails.serializer()).await()
    }


    @Serializable
    data class LatestSubmissions(
        val student_count: Long,
        val students: List<LatestSubmission>
    )

    @Serializable
    data class LatestSubmission(
        val student_id: String,
        val given_name: String,
        val family_name: String,
        val submission_id: String?,
        @Serializable(with = EzDateSerializer::class)
        val submission_time: EzDate?,
        val grade: Int?,
        val graded_by: ExerciseDAO.GraderType?,
        val groups: String?
    )

    fun getLatestSubmissions(courseId: String, courseExerciseId: String, groupId: String? = null) = doInPromise {
        debug { "Get latest submissions for course $courseId exercise $courseExerciseId" }
        val q = if (groupId != null) createQueryString("group" to groupId) else ""
        fetchEms("/teacher/courses/${courseId.encodeURIComponent()}/exercises/${courseExerciseId.encodeURIComponent()}/submissions/latest/students$q",
            ReqMethod.GET,
            successChecker = { http200 }).await()
            .parseTo(LatestSubmissions.serializer()).await()
    }
}