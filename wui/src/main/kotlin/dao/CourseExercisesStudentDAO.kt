package dao

import EzDate
import EzDateSerializer
import Icons
import components.ToastThing
import debug
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import queries.*
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.encodeURIComponent
import translation.Str
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
        val is_open: Boolean,
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


    @Serializable
    data class ExerciseDetails(
        val effective_title: String,
        val text_html: String?,
        @Serializable(with = EzDateSerializer::class)
        val deadline: EzDate?,
        val grader_type: ExerciseDAO.GraderType,
        val threshold: Int,
        val instructions_html: String?,
        val is_open: Boolean,
    )

    fun getCourseExerciseDetails(courseId: String, courseExId: String): Promise<ExerciseDetails> = doInPromise {
        debug { "Get student exercise details for ce $courseExId on course $courseId" }
        fetchEms(
            "/student/courses/${courseId.encodeURIComponent()}/exercises/${courseExId.encodeURIComponent()}",
            ReqMethod.GET, successChecker = { http200 },
            errorHandlers = listOf(ErrorHandlers.noCourseAccessMsg, ErrorHandlers.noVisibleExerciseMsg)
        ).await()
            .parseTo(ExerciseDetails.serializer()).await()
    }


    @Serializable
    data class StudentSubmissions(
        val submissions: List<StudentSubmission>,
        val count: Int
    )

    data class ValidGrade(val grade: Int, val grader_type: ExerciseDAO.GraderType)

    @Serializable
    data class StudentSubmission(
        val id: String,
        val number: Int,
        val solution: String,
        @Serializable(with = EzDateSerializer::class)
        val submission_time: EzDate,
        val autograde_status: AutogradeStatus,
        // TODO: add submission_status to service so we don't have to calculate that
//        val submission_status: CourseExercisesStudentDAO.SubmissionStatus,
        val grade_auto: Int?,
        val feedback_auto: String?,
        val grade_teacher: Int?,
        val feedback_teacher: String?
    ) {
        val validGrade: ValidGrade?
            get() = when {
                grade_teacher != null -> ValidGrade(grade_teacher, ExerciseDAO.GraderType.TEACHER)
                grade_auto != null -> ValidGrade(grade_auto, ExerciseDAO.GraderType.AUTO)
                else -> null
            }
    }

    enum class AutogradeStatus { NONE, IN_PROGRESS, COMPLETED, FAILED }

    fun getLatestSubmission(courseId: String, courseExId: String): Promise<StudentSubmission?> = doInPromise {
        getSubmissions(courseId, courseExId, 1).await().firstOrNull()
    }

    fun getSubmissions(courseId: String, courseExId: String, limit: Int = 1000): Promise<List<StudentSubmission>> =
        doInPromise {
            fetchEms(
                "/student/courses/${courseId.encodeURIComponent()}/exercises/${courseExId.encodeURIComponent()}/submissions/all?limit=$limit",
                ReqMethod.GET,
                successChecker = { http200 },
                errorHandlers = listOf(ErrorHandlers.noCourseAccessMsg, ErrorHandlers.noVisibleExerciseMsg)
            ).await()
                .parseTo(StudentSubmissions.serializer()).await().submissions
        }

    @Serializable
    data class StudentDraft(
        val solution: String,
        @Serializable(with = EzDateSerializer::class)
        val created_at: EzDate
    )

    fun getSubmissionDraft(courseId: String, courseExId: String): Promise<StudentDraft?> = doInPromise {
        val resp = fetchEms(
            "/student/courses/${courseId.encodeURIComponent()}/exercises/${courseExId.encodeURIComponent()}/draft",
            ReqMethod.GET,
            successChecker = { http200 or http204 },
            errorHandlers = listOf(ErrorHandlers.noCourseAccessMsg, ErrorHandlers.noVisibleExerciseMsg)
        ).await()

        if (resp.http200)
            resp.parseTo(StudentDraft.serializer()).await()
        else
            null
    }


    fun postSubmissionDraft(
        courseId: String, courseExId: String, solution: String,
    ): Promise<Unit> = doInPromise {
        fetchEms(
            "/student/courses/${courseId.encodeURIComponent()}/exercises/${courseExId.encodeURIComponent()}/draft",
            ReqMethod.POST,
            mapOf("solution" to solution),
            successChecker = { http200 },
        ).await()
    }

    fun postSubmission(courseId: String, courseExId: String, solution: String): Promise<Unit> = doInPromise {
        fetchEms(
            "/student/courses/${courseId.encodeURIComponent()}/exercises/${courseExId.encodeURIComponent()}/submissions",
            ReqMethod.POST,
            mapOf("solution" to solution),
            successChecker = { http200 },
            errorHandlers = listOf(
                ErrorHandlers.noCourseAccessMsg,
                ErrorHandlers.noVisibleExerciseMsg,
                {
                    it.handleByCode(RespError.COURSE_EXERCISE_CLOSED) {
                        ToastThing(Str.exerciseClosedForSubmissions, icon = Icons.errorUnf)
                    }
                })
        ).await()
    }

    @Serializable
    data class AwaitedSubmission(
        val id: String,
        val solution: String,
        @Serializable(with = EzDateSerializer::class)
        val submission_time: EzDate,
        val autograde_status: AutogradeStatus,
        val grade_auto: Int?,
        val feedback_auto: String?,
        val grade_teacher: Int?,
        val feedback_teacher: String?
    ) {
        val validGrade: ValidGrade?
            get() = when {
                grade_teacher != null -> ValidGrade(grade_teacher, ExerciseDAO.GraderType.TEACHER)
                grade_auto != null -> ValidGrade(grade_auto, ExerciseDAO.GraderType.AUTO)
                else -> null
            }
    }

    fun awaitAutograde(courseId: String, courseExId: String): Promise<AwaitedSubmission> = doInPromise {
        fetchEms(
            "/student/courses/${courseId.encodeURIComponent()}/exercises/${courseExId.encodeURIComponent()}/submissions/latest/await",
            ReqMethod.GET,
            successChecker = { http200 },
            errorHandlers = listOf(ErrorHandlers.noCourseAccessMsg, ErrorHandlers.noVisibleExerciseMsg)
        ).await()
            .parseTo(AwaitedSubmission.serializer()).await()
    }
}