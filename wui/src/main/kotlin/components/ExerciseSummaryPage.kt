package components

import Auth
import DateSerializer
import PageName
import ReqMethod
import Role
import Str
import debug
import debugFunStart
import errorMessage
import fetchEms
import getContainer
import getElemById
import getElemByIdAs
import getElemByIdOrNull
import getNodelistBySelector
import http200
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import libheaders.CodeMirror
import libheaders.Materialize
import objOf
import onVanillaClick
import org.w3c.dom.*
import parseTo
import queries.BasicCourseInfo
import tmRender
import toEstonianString
import toJsObj
import kotlin.browser.window
import kotlin.dom.clear
import kotlin.js.Date

object ExerciseSummaryPage : EasyPage() {

    @Serializable
    data class TeacherExercise(
            val title: String,
            val title_alias: String?,
            val instructions_html: String?,
            val text_html: String?,
            @Serializable(with = DateSerializer::class)
            val hard_deadline: Date?,
            @Serializable(with = DateSerializer::class)
            val soft_deadline: Date?,
            val grader_type: GraderType,
            val threshold: Int,
            @Serializable(with = DateSerializer::class)
            val last_modified: Date,
            val student_visible: Boolean,
            val assessments_student_visible: Boolean,
            val grading_script: String?,
            val container_image: String?,
            val max_time_sec: Int?,
            val max_mem_mb: Int?,
            val assets: List<AutoAsset>?,
            val executors: List<AutoExecutor>?
    )

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

    enum class GraderType {
        AUTO, TEACHER
    }

    @Serializable
    data class AutoassResult(
            val grade: Int,
            val feedback: String?
    )

    @Serializable
    data class TeacherStudents(
            val student_count: Int,
            val students: List<TeacherStudent>
    )

    @Serializable
    data class TeacherStudent(
            val student_id: String,
            val given_name: String,
            val family_name: String,
            @Serializable(with = DateSerializer::class)
            val submission_time: Date?,
            val grade: Int?,
            val graded_by: GraderType?
    )

    @Serializable
    data class TeacherSubmission(
            val id: String,
            val solution: String,
            @Serializable(with = DateSerializer::class)
            val created_at: Date,
            val grade_auto: Int?,
            val feedback_auto: String?,
            val grade_teacher: Int?,
            val feedback_teacher: String?
    )


    override val pageName: Any
        get() = PageName.EXERCISE_SUMMARY

    override fun pathMatches(path: String) =
            path.matches("^/courses/\\w+/exercises/\\w+/summary/?$")

    override fun build(pageStateStr: String?) {
        val pathIds = extractSanitizedPathIds(window.location.pathname)
        val courseId = pathIds.courseId
        val courseExerciseId = pathIds.exerciseId

        when (Auth.activeRole) {
            Role.STUDENT -> buildStudentExercise(courseId)
            Role.TEACHER, Role.ADMIN -> buildTeacherExercise(courseId, courseExerciseId)
        }
    }

    private fun buildTeacherExercise(courseId: String, courseExerciseId: String) = MainScope().launch {
        val fl = debugFunStart("buildTeacherExercise")

        getContainer().innerHTML = tmRender("tm-teach-exercise", mapOf(
                "exerciseLabel" to Str.tabExerciseLabel(),
                "testingLabel" to Str.tabTestingLabel(),
                "studentSubmLabel" to Str.tabSubmissionsLabel()
        ))

        Materialize.Tabs.init(getElemById("tabs"))

        // Could be optimised to load exercise details & students in parallel,
        // requires passing an exercisePromise to buildStudents since the threshold is needed for painting
        val exerciseDetails = buildTeacherSummaryAndCrumbs(courseId, courseExerciseId)
        buildTeacherTesting(courseId, courseExerciseId)
        buildTeacherStudents(courseId, courseExerciseId, exerciseDetails.threshold)

        Materialize.Tooltip.init(getNodelistBySelector(".tooltipped"))
        fl?.end()
    }

    private suspend fun buildTeacherSummaryAndCrumbs(courseId: String, courseExerciseId: String): TeacherExercise {
        val fl = debugFunStart("buildTeacherSummaryAndCrumbs")

        val exercisePromise = fetchEms("/teacher/courses/$courseId/exercises/$courseExerciseId", ReqMethod.GET)

        val courseTitle = BasicCourseInfo.get(courseId).await().title

        val exerciseResp = exercisePromise.await()
        if (!exerciseResp.http200) {
            errorMessage { Str.somethingWentWrong() }
            error("Fetching exercises failed with status ${exerciseResp.status}")
        }

        val exercise = exerciseResp.parseTo(TeacherExercise.serializer()).await()

        getElemById("crumbs").innerHTML = tmRender("tm-teach-exercise-crumbs", mapOf(
                "coursesLabel" to Str.myCourses(),
                "coursesHref" to "/courses",
                "courseTitle" to courseTitle,
                "courseHref" to "/courses/$courseId/exercises",
                "exerciseTitle" to (exercise.title_alias ?: exercise.title)
        ))

        getElemById("exercise").innerHTML = tmRender("tm-teach-exercise-summary", mapOf(
                "softDeadlineLabel" to Str.softDeadlineLabel(),
                "hardDeadlineLabel" to Str.hardDeadlineLabel(),
                "graderTypeLabel" to Str.graderTypeLabel(),
                "thresholdLabel" to Str.thresholdLabel(),
                "studentVisibleLabel" to Str.studentVisibleLabel(),
                "assStudentVisibleLabel" to Str.assStudentVisibleLabel(),
                "lastModifiedLabel" to Str.lastModifiedLabel(),
                "softDeadline" to exercise.soft_deadline?.toEstonianString(),
                "hardDeadline" to exercise.hard_deadline?.toEstonianString(),
                "graderType" to if (exercise.grader_type == GraderType.AUTO) Str.graderTypeAuto() else Str.graderTypeTeacher(),
                "threshold" to exercise.threshold,
                "studentVisible" to Str.translateBoolean(exercise.student_visible),
                "assStudentVisible" to Str.translateBoolean(exercise.assessments_student_visible),
                "lastModified" to exercise.last_modified.toEstonianString(),
                "exerciseTitle" to (exercise.title_alias ?: exercise.title),
                "exerciseText" to exercise.text_html
        ))

        fl?.end()
        return exercise
    }

    private fun buildTeacherTesting(courseId: String, courseExerciseId: String) {

        suspend fun postSolution(solution: String): AutoassResult {
            debug { "Posting submission ${solution.substring(0, 15)}..." }
            val resp = fetchEms("/teacher/courses/$courseId/exercises/$courseExerciseId/autoassess",
                    ReqMethod.POST, mapOf("solution" to solution))
                    .await()
            if (!resp.http200) {
                errorMessage { Str.somethingWentWrong() }
                error("Autoassessing failed with status ${resp.status}")
            }
            val result = resp.parseTo(AutoassResult.serializer()).await()
            debug { "Received result, grade: ${result.grade}" }
            return result
        }


        val fl = debugFunStart("buildTeacherTesting")
        getElemById("testing").innerHTML = tmRender("tm-teach-exercise-testing", mapOf(
                "checkLabel" to Str.doAutoAssess()
        ))
        val editor = CodeMirror.fromTextArea(getElemById("testing-submission"),
                objOf("mode" to "python",
                        "lineNumbers" to true,
                        "autoRefresh" to true,
                        "viewportMargin" to 100))

        val submitButton = getElemByIdAs<HTMLButtonElement>("testing-submit")

        submitButton.onVanillaClick(true) {
            MainScope().launch {
                submitButton.disabled = true
                submitButton.textContent = Str.autoAssessing()
                val autoAssessmentWrap = getElemById("testing-assessment")
                autoAssessmentWrap.innerHTML = tmRender("tm-exercise-auto-feedback", mapOf(
                        "autoLabel" to Str.autoAssessmentLabel(),
                        "autoGradeLabel" to Str.autoGradeLabel(),
                        "grade" to "-",
                        "feedback" to Str.autoAssessing()
                ))
                val solution = editor.getValue()
                val result = postSolution(solution)
                autoAssessmentWrap.innerHTML = tmRender("tm-exercise-auto-feedback", mapOf(
                        "autoLabel" to Str.autoAssessmentLabel(),
                        "autoGradeLabel" to Str.autoGradeLabel(),
                        "grade" to result.grade.toString(),
                        "feedback" to result.feedback
                ))
                submitButton.textContent = Str.doAutoAssess()
                submitButton.disabled = false
            }
        }
        fl?.end()
    }

    private suspend fun buildTeacherStudents(courseId: String, courseExerciseId: String, threshold: Int) {
        val fl = debugFunStart("buildTeacherStudents")

        val studentsPromise = fetchEms("/teacher/courses/$courseId/exercises/$courseExerciseId/submissions/latest/students", ReqMethod.GET)
        val studentsResp = studentsPromise.await()
        if (!studentsResp.http200) {
            errorMessage { Str.somethingWentWrong() }
            error("Fetching student submissions failed with status ${studentsResp.status}")
        }

        val teacherStudents = studentsResp.parseTo(TeacherStudents.serializer()).await()

        val studentArray = teacherStudents.students.map { student ->
            val studentMap = mutableMapOf<String, Any?>(
                    "id" to student.student_id,
                    "givenName" to student.given_name,
                    "familyName" to student.family_name,
                    "time" to student.submission_time?.toEstonianString(),
                    "points" to student.grade?.toString()
            )

            when (student.graded_by) {
                GraderType.AUTO -> {
                    studentMap["evalAuto"] = true
                }
                GraderType.TEACHER -> {
                    studentMap["evalTeacher"] = true
                }
            }

            if (student.grade == null) {
                if (student.submission_time == null)
                    studentMap["unstarted"] = true
                else
                    studentMap["evalMissing"] = true
            } else {
                if (student.grade >= threshold)
                    studentMap["completed"] = true
                else
                    studentMap["started"] = true
            }

            studentMap.toJsObj()
        }.toTypedArray()

        getElemById("students").innerHTML = tmRender("tm-teach-exercise-students",
                mapOf("students" to studentArray))

        getNodelistBySelector("[data-student-id]").asList().forEach {
            if (it is Element) {
                val id = it.getAttribute("data-student-id")
                        ?: error("No data-student-id found on student item")
                val givenName = it.getAttribute("data-given-name")
                        ?: error("No data-given-name found on student item")
                val familyName = it.getAttribute("data-family-name")
                        ?: error("No data-family-name found on student item")

                it.onVanillaClick(true) {
                    buildStudentTab(courseId, courseExerciseId, threshold, id, givenName, familyName)
                }

            } else {
                error("Student item is not an Element")
            }
        }

        fl?.end()
    }

    private fun buildStudentTab(courseId: String, courseExerciseId: String, threshold: Int,
                                studentId: String, givenName: String, familyName: String) {

        suspend fun addAssessment(grade: Int, feedback: String, submissionId: String) {
            val assMap: MutableMap<String, Any> = mutableMapOf("grade" to grade)
            if (feedback.isNotBlank())
                assMap["feedback"] = feedback

            debug { "Posting assessment $assMap" }

            val assResp = fetchEms("/teacher/courses/$courseId/exercises/$courseExerciseId/submissions/$submissionId/assessments",
                    ReqMethod.POST,
                    assMap)
                    .await()

            if (!assResp.http200) {
                errorMessage { Str.somethingWentWrong() }
                error("Posting assessment failed with status ${assResp.status}")
            }
        }

        fun toggleAddGradeBox(submissionId: String) {
            if (getElemByIdOrNull("add-grade-wrap") == null) {
                // Grading box is not visible
                debug { "Open add grade" }
                getElemById("add-grade-section").innerHTML = tmRender("tm-teach-exercise-add-grade", mapOf(
                        "feedbackLabel" to Str.addAssessmentFeedbackLabel(),
                        "gradeLabel" to Str.addAssessmentGradeLabel(),
                        "gradeValidationError" to Str.addAssessmentGradeValidErr(),
                        "addGradeButton" to Str.addAssessmentButtonLabel()
                ))

                getElemById("grade-button").onVanillaClick(true) {
                    val grade = getElemByIdAs<HTMLInputElement>("grade").valueAsNumber.toInt()
                    val feedback = getElemByIdAs<HTMLTextAreaElement>("feedback").value
                    MainScope().launch {
                        addAssessment(grade, feedback, submissionId)
                        toggleAddGradeBox(submissionId)
                        getElemById("assessment-teacher").innerHTML = tmRender("tm-exercise-teacher-feedback", mapOf(
                                "teacherLabel" to Str.teacherAssessmentLabel(),
                                "teacherGradeLabel" to Str.teacherGradeLabel(),
                                "grade" to grade.toString(),
                                "feedback" to feedback
                        ))
                        buildTeacherStudents(courseId, courseExerciseId, threshold)
                    }
                }

                getElemById("add-grade-link").innerHTML = Str.closeAssessmentLink()

            } else {
                // Grading box is visible
                debug { "Close add grade" }
                getElemById("add-grade-section").clear()
                getElemById("add-grade-link").innerHTML = Str.addAssessmentLink()
            }
        }


        getElemById("tab-student").textContent = "$givenName ${familyName[0]}"

        val tabs = Materialize.Tabs.getInstance(getElemById("tabs"))
        tabs.select("student")
        tabs.updateTabIndicator()

        MainScope().launch {
            val submissionResp =
                    fetchEms("/teacher/courses/$courseId/exercises/$courseExerciseId/submissions/latest/students/$studentId",
                            ReqMethod.GET).await()

            if (!submissionResp.http200) {
                errorMessage { Str.somethingWentWrong() }
                error("Fetching student submission failed with status ${submissionResp.status}")
            }

            val submission = submissionResp.parseTo(TeacherSubmission.serializer()).await()

            getElemById("student").innerHTML = tmRender("tm-teach-exercise-student-submission", mapOf(
                    "timeLabel" to Str.submissionTimeLabel(),
                    "time" to submission.created_at.toEstonianString(),
                    "addGradeLink" to Str.addAssessmentLink(),
                    "solution" to submission.solution
            ))

            if (submission.grade_auto != null) {
                getElemById("assessment-auto").innerHTML = tmRender("tm-exercise-auto-feedback", mapOf(
                        "autoLabel" to Str.autoAssessmentLabel(),
                        "autoGradeLabel" to Str.autoGradeLabel(),
                        "grade" to submission.grade_auto.toString(),
                        "feedback" to submission.feedback_auto
                ))
            }
            if (submission.grade_teacher != null) {
                getElemById("assessment-teacher").innerHTML = tmRender("tm-exercise-teacher-feedback", mapOf(
                        "teacherLabel" to Str.teacherAssessmentLabel(),
                        "teacherGradeLabel" to Str.teacherGradeLabel(),
                        "grade" to submission.grade_teacher.toString(),
                        "feedback" to submission.feedback_teacher
                ))
            }

            CodeMirror.fromTextArea(getElemById("student-submission"), objOf(
                    "mode" to "python",
                    "lineNumbers" to true,
                    "autoRefresh" to true,
                    "viewportMargin" to 100,
                    "readOnly" to true))

            getElemById("add-grade-link").onVanillaClick(true) { toggleAddGradeBox(submission.id) }
        }
    }


    private fun buildStudentExercise(courseId: String) {


    }

    data class PathIds(val courseId: String, val exerciseId: String)

    private fun extractSanitizedPathIds(path: String): PathIds {
        val match = path.match("^/courses/(\\w+)/exercises/(\\w+)/summary/?\$")
        if (match != null && match.size == 3) {
            return PathIds(match[1], match[2])
        } else {
            error("Unexpected match on path: ${match?.joinToString()}")
        }
    }
}