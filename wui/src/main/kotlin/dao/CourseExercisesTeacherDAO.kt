package dao

import EzDate
import EzDateSerializer
import components.EzCollComp
import dao.CourseExercisesStudentDAO.SubmissionStatus
import debug
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import queries.*
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.encodeURIComponent
import saveAsFile
import kotlin.js.Promise

object CourseExercisesTeacherDAO {

    @Serializable
    data class CourseExerciseWithSubmissions(
        val course_exercise_id: String,
        val exercise_id: String,
        val library_title: String,
        val title_alias: String?,
        val effective_title: String,
        val grade_threshold: Int,
        val student_visible: Boolean,
        @Serializable(with = EzDateSerializer::class)
        val student_visible_from: EzDate?,
        @Serializable(with = EzDateSerializer::class)
        val soft_deadline: EzDate?,
        @Serializable(with = EzDateSerializer::class)
        val hard_deadline: EzDate?,
        val grader_type: ExerciseDAO.GraderType,
        val ordering_idx: Int,
        val unstarted_count: Int,
        val ungraded_count: Int,
        val started_count: Int,
        val completed_count: Int,
        val latest_submissions: List<LatestStudentSubmission>,
    ) {
        val effectiveTitle = title_alias ?: library_title
        val isVisibleNow
            get() = student_visible_from != null && EzDate.now() >= student_visible_from
    }

    @Serializable
    data class LatestStudentSubmission(
        val submission: StudentSubmission?,
        val status: SubmissionStatus,
        val student_id: String,
        val given_name: String,
        val family_name: String,
        override val groups: List<ParticipantsDAO.CourseGroup>,
    ) : EzCollComp.WithGroups {
        val name: String
            get() = "$given_name $family_name"
    }

    @Serializable
    data class StudentSubmission(
        val id: String,
        val submission_number: Int,
        @Serializable(with = EzDateSerializer::class)
        val time: EzDate,
        val seen: Boolean,
        val grade: Grade?,
    )

    @Serializable
    data class Grade(
        val grade: Int,
        val is_autograde: Boolean,
    )

    @Serializable
    private data class CourseExercisesWithSubmissions(val exercises: List<CourseExerciseWithSubmissions>)

    fun getCourseExercises(courseId: String, groupId: String? = null): Promise<List<CourseExerciseWithSubmissions>> =
        doInPromise {
            debug { "Get exercises for teacher course $courseId" }
            val q = if (groupId != null) createQueryString("group" to groupId) else ""
            fetchEms(
                "/teacher/courses/${courseId.encodeURIComponent()}/exercises$q", ReqMethod.GET,
                successChecker = { http200 }, errorHandlers = listOf(ErrorHandlers.noCourseAccessMsg)
            ).await()
                .parseTo(CourseExercisesWithSubmissions.serializer()).await().exercises
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
        @Serializable(with = EzDateSerializer::class)
        val student_visible_from: EzDate?,
        @Serializable(with = EzDateSerializer::class)
        val hard_deadline: EzDate?,
        @Serializable(with = EzDateSerializer::class)
        val soft_deadline: EzDate?,
        val grader_type: ExerciseDAO.GraderType,
        val solution_file_name: String,
        val solution_file_type: ExerciseDAO.SolutionFileType,
        val threshold: Int,
        @Serializable(with = EzDateSerializer::class)
        val last_modified: EzDate,
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


    fun getLatestSubmissions(courseId: String, courseExerciseId: String, groupId: String? = null) = doInPromise {
        debug { "Get latest submissions for course $courseId exercise $courseExerciseId" }
        val q = if (groupId != null) createQueryString("group" to groupId) else ""


        fetchEms("/teacher/courses/${courseId.encodeURIComponent()}/exercises/${courseExerciseId.encodeURIComponent()}/submissions/latest/students$q",
            ReqMethod.GET,
            successChecker = { http200 }).await()
            .parseTo(CourseExerciseWithSubmissions.serializer()).await()
    }

    @Serializable
    data class StudentSubmissionOld(
        val id: String,
        val submission_number: Int,
        @Serializable(with = EzDateSerializer::class)
        val created_at: EzDate,
        val status: SubmissionStatus,
        val grade: Grade?,
    )

    @Serializable
    data class AllSubmissions(val submissions: List<StudentSubmissionOld>)

    fun getAllSubmissionsForStudent(courseId: String, courseExerciseId: String, studentId: String) = doInPromise {
        debug { "Get all submissions for student $studentId on course $courseId exercise $courseExerciseId" }

        fetchEms("/teacher/courses/${courseId.encodeURIComponent()}/exercises/${courseExerciseId.encodeURIComponent()}/submissions/all/students/${studentId.encodeURIComponent()}",
            ReqMethod.GET,
            successChecker = { http200 }).await()
            .parseTo(AllSubmissions.serializer()).await()
    }

    @Serializable
    data class StudentSubmissionDetails(
        val id: String,
        val submission_number: Int,
        val solution: String,
        val seen: Boolean,
        @Serializable(with = EzDateSerializer::class)
        val created_at: EzDate,
        val autograde_status: CourseExercisesStudentDAO.AutogradeStatus,
        val grade: Grade?,
        val auto_assessment: AutomaticAssessment?,
    )

    @Serializable
    data class AutomaticAssessment(
        val grade: Int,
        val feedback: String?,
    )

    fun getSubmissionDetails(courseId: String, courseExerciseId: String, submissionId: String) = doInPromise {
        fetchEms("/teacher/courses/${courseId.encodeURIComponent()}/exercises/${courseExerciseId.encodeURIComponent()}/submissions/${submissionId.encodeURIComponent()}",
            ReqMethod.GET, successChecker = { http200 }).await()
            .parseTo(StudentSubmissionDetails.serializer()).await()
    }

    @Serializable
    data class Feedback(
        val feedback_html: String,
        val feedback_adoc: String
    )

    @Serializable
    data class Teacher(
        val id: String,
        val given_name: String,
        val family_name: String
    )

    @Serializable
    data class TeacherActivity(
        val id: String,
        val submission_id: String,
        val submission_number: Int,
        @Serializable(with = EzDateSerializer::class)
        val created_at: EzDate,
        val grade: Int?,
        @Serializable(with = EzDateSerializer::class)
        val edited_at: EzDate?,
        val feedback: Feedback?,
        val teacher: Teacher
    )

    @Serializable
    data class Activities(
        val teacher_activities: List<TeacherActivity>,
    )

    fun getActivityForStudent(courseId: String, courseExerciseId: String, studentId: String) = doInPromise {
        fetchEms("/teacher/courses/${courseId.encodeURIComponent()}/exercises/${courseExerciseId.encodeURIComponent()}/students/${studentId.encodeURIComponent()}/activities",
            ReqMethod.GET, successChecker = { http200 }).await()
            .parseTo(Activities.serializer()).await()
    }

    fun addComment(
        courseId: String, courseExerciseId: String, submissionId: String, commentAdoc: String?, sendEmail: Boolean
    ) = doInPromise {
        fetchEms("/teacher/courses/${courseId.encodeURIComponent()}/exercises/${courseExerciseId.encodeURIComponent()}/submissions/${submissionId.encodeURIComponent()}/feedback",
            ReqMethod.POST,
            mapOf(
                "feedback_adoc" to commentAdoc,
                "notify_student" to sendEmail,
            ),
            successChecker = { http200 }).await()
    }


    fun editComment(
        courseId: String, courseExerciseId: String, submissionId: String, activityId: String, commentAdoc: String?,
        sendEmail: Boolean
    ) = doInPromise {
        fetchEms("/teacher/courses/${courseId.encodeURIComponent()}/exercises/${courseExerciseId.encodeURIComponent()}/submissions/${submissionId.encodeURIComponent()}/feedback",
            ReqMethod.PUT,
            mapOf(
                "teacher_activity_id" to activityId,
                "feedback_adoc" to commentAdoc,
                "notify_student" to sendEmail,
            ),
            successChecker = { http200 }).await()
    }

    fun deleteComment(courseId: String, courseExerciseId: String, submissionId: String, activityId: String) =
        editComment(courseId, courseExerciseId, submissionId, activityId, null, false)

    fun setSubmissionSeenStatus(
        courseId: String, courseExerciseId: String, seenStatus: Boolean, submissionIds: List<String>
    ) = doInPromise {
        fetchEms("/teacher/courses/${courseId.encodeURIComponent()}/exercises/${courseExerciseId.encodeURIComponent()}/submissions/seen",
            ReqMethod.POST,
            mapOf(
                "submissions" to submissionIds.map {
                    mapOf(
                        "id" to it
                    )
                },
                "seen" to seenStatus,
            ),
            successChecker = { http200 }).await()
    }

    fun changeGrade(
        courseId: String, courseExerciseId: String, submissionId: String, points: Int, sendEmail: Boolean
    ) = doInPromise {
        fetchEms("/teacher/courses/${courseId.encodeURIComponent()}/exercises/${courseExerciseId.encodeURIComponent()}/submissions/${submissionId.encodeURIComponent()}/grade",
            ReqMethod.POST,
            mapOf(
                "grade" to points,
                "notify_student" to sendEmail,
            ),
            successChecker = { http200 }).await()
    }

    fun downloadSubmissions(
        courseId: String, courseExerciseId: String, submissionIds: List<String>
    ) = doInPromise {
        val resp = fetchEms("/export/courses/${courseId.encodeURIComponent()}/exercises/${courseExerciseId.encodeURIComponent()}/submissions",
            ReqMethod.POST,
            mapOf(
                "submissions" to submissionIds.map {
                    mapOf(
                        "id" to it
                    )
                },
            ),
            successChecker = { http200 }).await()

        val contentDisposition = resp.headers.get("Content-disposition")
        val extractedFilename = contentDisposition?.let {
            Regex("filename=\"?(.+)\"?").find(it)?.groupValues?.getOrNull(1)
        }

        val filename = when {
            extractedFilename != null -> extractedFilename
            submissionIds.size > 1 -> "submissions.zip"
            else -> "submission.py"
        }

        resp.blob().await().saveAsFile(filename)
    }
}
