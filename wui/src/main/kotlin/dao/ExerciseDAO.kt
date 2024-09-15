package dao

import EzDate
import EzDateSerializer
import Icons
import blankToNull
import components.ToastThing
import dao.CoursesTeacherDAO.getEffectiveCourseTitle
import debug
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import pages.exercise_library.DirAccess
import queries.*
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.encodeURIComponent
import translation.Str
import kotlin.js.Promise

object ExerciseDAO {

    @Serializable
    data class Exercise(
        var is_public: Boolean,
        var grader_type: GraderType,
        var solution_file_name: String,
        var solution_file_type: SolutionFileType,
        var title: String,
        var text_adoc: String? = null,
        var grading_script: String? = null,
        var container_image: String? = null,
        var max_time_sec: Int? = null,
        var max_mem_mb: Int? = null,
        var assets: List<Asset>? = null,
        var executors: List<Executor>? = null,
        val dir_id: String,
        val effective_access: DirAccess,
        @Serializable(with = EzDateSerializer::class)
        val created_at: EzDate,
        val owner_id: String,
        @Serializable(with = EzDateSerializer::class)
        val last_modified: EzDate,
        val last_modified_by_id: String,
        val text_html: String? = null,
        val on_courses: List<OnCourse>,
        val on_courses_no_access: Int,
        val is_anonymous_autoassess_enabled: Boolean,
        val anonymous_autoassess_template: String?,
    )

    @Serializable
    data class Asset(
        val file_name: String,
        val file_content: String
    )

    @Serializable
    data class Executor(
        val id: String,
        val name: String
    )

    @Serializable
    data class OnCourse(
        val id: String,
        private val title: String,
        private val alias: String?,
        val course_exercise_id: String,
        val course_exercise_title_alias: String?
    ) {
        val effectiveTitle: String
            get() = getEffectiveCourseTitle(title, alias)
    }

    enum class GraderType {
        AUTO, TEACHER;

        companion object {
            const val ICON_AUTO = Icons.robot
            const val ICON_TEACHER = Icons.teacherFace
        }

        fun icon() = when (this) {
            AUTO -> ICON_AUTO
            TEACHER -> ICON_TEACHER
        }
    }

    class NoLibAccessException : Exception()

    fun getExercise(exerciseId: String): Promise<Exercise> = doInPromise {
        debug { "Getting exercise $exerciseId" }

        // TODO: should have a default pattern to accomplish this - throwing an exception based on resp (e.g. code)
        try {
            fetchEms("/exercises/${exerciseId.encodeURIComponent()}", ReqMethod.GET,
                successChecker = { http200 },
                errorHandler = {
                    it.handleByCode(RespError.NO_EXERCISE_ACCESS) {
                        throw NoLibAccessException()
                    }
                }).await()
                .parseTo(Exercise.serializer()).await()
        } catch (e: HandledResponseError) {
            throw if (e.errorHandlerException.await() is NoLibAccessException) e.errorHandlerException.await() else e
        }
    }


    data class UpdatedExercise(
        val title: String,
        val textAdoc: String?,
        val solutionFileName: String,
        val solutionFileType: SolutionFileType,
        val autoeval: Autoeval?,
    )

    data class Autoeval(
        val containerImage: String,
        val evalScript: String,
        val assets: Map<String, String>,
        val maxTime: Int,
        val maxMem: Int,
    )

    fun updateExercise(exerciseId: String, exercise: UpdatedExercise): Promise<Unit> = doInPromise {
        debug { "Updating exercise ${exercise.title} ($exerciseId)" }

        val body = exercise.let {
            mapOf(
                "title" to it.title,
                // avoid overwriting html with empty adoc for html-only exercises
                "text_adoc" to it.textAdoc.blankToNull(),
                "grader_type" to if (it.autoeval != null) GraderType.AUTO.name else GraderType.TEACHER.name,
                "solution_file_name" to it.solutionFileName,
                "solution_file_type" to it.solutionFileType.name,
                "container_image" to it.autoeval?.containerImage,
                "grading_script" to it.autoeval?.evalScript,
                "max_time_sec" to it.autoeval?.maxTime,
                "max_mem_mb" to it.autoeval?.maxMem,
                "assets" to it.autoeval?.assets?.map { mapOf("file_name" to it.key, "file_content" to it.value) },
            )
        }

        fetchEms("/exercises/${exerciseId.encodeURIComponent()}", ReqMethod.PUT, body,
            successChecker = { http200 }).await()
    }

    fun setExerciseEmbed(exerciseId: String, enabled: Boolean): Promise<Unit> = doInPromise {
        debug { "Setting exercise $exerciseId embed enabled: $enabled" }

        val body = mapOf(
            "anonymous_autoassess_enabled" to enabled,
        )

        fetchEms("/exercises/${exerciseId.encodeURIComponent()}", ReqMethod.PATCH, body,
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


    @Serializable
    data class Similarity(
        val submissions: List<SimilarSubmission>,
        val scores: List<SimilarityScore>
    )

    @Serializable
    data class SimilarSubmission(
        val id: String,
        @Serializable(with = EzDateSerializer::class)
        val created_at: EzDate,
        val solution: String,
        val given_name: String,
        val family_name: String,
        val course_title: String
    )

    @Serializable
    data class SimilarityScore(
        val sub_1: String,
        val sub_2: String,
        val score_a: Int,
        val score_b: Int
    )

    fun checkSimilarity(exerciseId: String, courses: List<String>, submissionIds: List<String>): Promise<Similarity> =
        doInPromise {
            debug { "Checking similarity for exercise $exerciseId on courses $courses" }
            fetchEms("/exercises/${exerciseId.encodeURIComponent()}/similarity", ReqMethod.POST,
                mapOf(
                    "courses" to courses.map { mapOf("id" to it) },
                    "submissions" to submissionIds.map { mapOf("id" to it) },
                ),
                successChecker = { http200 }
            ).await().parseTo(Similarity.serializer()).await()
        }


    fun deleteExercise(exerciseId: String) = doInPromise {
        debug { "Deleting exercise $exerciseId" }
        fetchEms(
            "/exercises/${exerciseId.encodeURIComponent()}",
            ReqMethod.DELETE, successChecker = { http200 },
            errorHandler = {
                it.handleByCode(RespError.EXERCISE_USED_ON_COURSE) {
                    ToastThing(Str.cannotDeleteExerciseUsedOnCourse, icon = ToastThing.ERROR_INFO)
                }
            }
        ).await()

        Unit
    }

    enum class SolutionFileType {
        TEXT_EDITOR,
        TEXT_UPLOAD,
    }


    @Serializable
    data class AutoAssessment(
        val grade: Int,
        val feedback: String?,
        @Serializable(with = EzDateSerializer::class)
        val timestamp: EzDate = EzDate.now(),
    )

    fun autoassess(exerciseId: String, solution: String, courseId: String? = null) = doInPromise {
        debug { "Autoassessing solution to exercise $exerciseId" }

        val q = if (courseId != null) {
            createQueryString("course" to courseId)
        } else ""

        fetchEms("/exercises/${exerciseId.encodeURIComponent()}/testing/autoassess$q",
            ReqMethod.POST, mapOf("solution" to solution), successChecker = { http200 }
        ).await().parseTo(AutoAssessment.serializer()).await()
    }

    @Serializable
    data class HtmlPreview(
        val content: String
    )

    fun previewAdocContent(adoc: String) = doInPromise {
        debug { "Preview adoc content" }
        fetchEms("/preview/adoc", ReqMethod.POST, mapOf("content" to adoc),
            successChecker = { http200 }).await()
            .parseTo(HtmlPreview.serializer()).await().content
    }
}