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
import getElemBySelector
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
import warn
import kotlin.browser.window
import kotlin.dom.addClass
import kotlin.dom.clear
import kotlin.dom.removeClass
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
    data class TeacherSubmissions(
            val submissions: List<TeacherSubmission>,
            val count: Int
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

    @Serializable
    data class StudentExercise(
            val effective_title: String,
            val text_html: String?,
            @Serializable(with = DateSerializer::class)
            val deadline: Date?,
            val grader_type: GraderType,
            val threshold: Int,
            val instructions_html: String?
    )

    enum class AutogradeStatus {
        NONE,
        IN_PROGRESS,
        COMPLETED,
        FAILED
    }

    @Serializable
    data class StudentSubmissions(
            val submissions: List<StudentSubmission>,
            val count: Int
    )

    @Serializable
    data class StudentSubmission(
            val id: String,
            val solution: String,
            @Serializable(with = DateSerializer::class)
            val submission_time: Date,
            val autograde_status: AutogradeStatus,
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
            Role.STUDENT -> buildStudentExercise(courseId, courseExerciseId)
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

        getElemById("crumbs").innerHTML = tmRender("tm-exercise-crumbs", mapOf(
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
                autoAssessmentWrap.innerHTML = renderAutoAssessment(result.grade, result.feedback)
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

        getElemById("students").innerHTML = tmRender("tm-teach-exercise-students", mapOf(
                "students" to studentArray,
                "autoLabel" to Str.gradedAutomatically(),
                "teacherLabel" to Str.gradedByTeacher(),
                "missingLabel" to Str.notGradedYet()
        ))

        getNodelistBySelector("[data-student-id]").asList().forEach {
            if (it is Element) {
                val id = it.getAttribute("data-student-id")
                        ?: error("No data-student-id found on student item")
                val givenName = it.getAttribute("data-given-name")
                        ?: error("No data-given-name found on student item")
                val familyName = it.getAttribute("data-family-name")
                        ?: error("No data-family-name found on student item")

                it.onVanillaClick(true) {
                    buildStudentTab(courseId, courseExerciseId, threshold, id, givenName, familyName, false)
                }

            } else {
                error("Student item is not an Element")
            }
        }

        Materialize.Tooltip.init(getNodelistBySelector(".tooltipped"))

        fl?.end()
    }

    private fun buildStudentTab(courseId: String, courseExerciseId: String, threshold: Int,
                                studentId: String, givenName: String, familyName: String, isAllSubsOpen: Boolean) {

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
                        getElemById("assessment-teacher").innerHTML = renderTeacherAssessment(grade, feedback)
                        buildTeacherStudents(courseId, courseExerciseId, threshold)
                    }
                }

                getElemById("add-grade-link").textContent = Str.closeToggleLink()

            } else {
                // Grading box is visible
                debug { "Close add grade" }
                getElemById("add-grade-section").clear()
                getElemById("add-grade-link").textContent = Str.addAssessmentLink()
            }
        }

        fun paintSubmission(id: String, number: Int, time: Date, solution: String, isLast: Boolean,
                            gradeAuto: Int?, feedbackAuto: String?, gradeTeacher: Int?, feedbackTeacher: String?) {

            getElemById("submission-part").innerHTML = tmRender("tm-teach-exercise-student-submission-sub", mapOf(
                    "id" to id,
                    "submissionLabel" to Str.submissionHeading(),
                    "submissionNo" to number,
                    "latestSubmissionLabel" to Str.latestSubmissionSuffix(),
                    "notLatestSubmissionLabel" to Str.oldSubmissionNote(),
                    "notLatestSubmissionLink" to Str.toLatestSubmissionLink(),
                    "isLatest" to isLast,
                    "timeLabel" to Str.submissionTimeLabel(),
                    "time" to time.toEstonianString(),
                    "addGradeLink" to Str.addAssessmentLink(),
                    "solution" to solution
            ))

            if (gradeAuto != null) {
                getElemById("assessment-auto").innerHTML =
                        renderAutoAssessment(gradeAuto, feedbackAuto)
            }
            if (gradeTeacher != null) {
                getElemById("assessment-teacher").innerHTML =
                        renderTeacherAssessment(gradeTeacher, feedbackTeacher)
            }

            CodeMirror.fromTextArea(getElemById("student-submission"), objOf(
                    "mode" to "python",
                    "lineNumbers" to true,
                    "autoRefresh" to true,
                    "viewportMargin" to 100,
                    "readOnly" to true))

            getElemByIdOrNull("add-grade-link")?.onVanillaClick(true) { toggleAddGradeBox(id) }

            getElemByIdOrNull("last-submission-link")?.onVanillaClick(true) {
                val isAllSubsBoxOpen = getElemByIdOrNull("all-submissions-wrap") != null
                buildStudentTab(courseId, courseExerciseId, threshold, studentId, givenName, familyName, isAllSubsBoxOpen)
            }
        }

        suspend fun toggleSubmissionsBox() {
            if (getElemByIdOrNull("all-submissions-wrap") == null) {
                // Box is not visible yet
                debug { "Open all submissions" }
                getElemById("all-submissions-section").innerHTML = tmRender("tm-teach-exercise-all-submissions-placeholder", mapOf(
                        "text" to Str.loadingAllSubmissions()
                ))
                getElemById("all-submissions-link").textContent = Str.closeToggleLink()

                val submissionResp =
                        fetchEms("/teacher/courses/$courseId/exercises/$courseExerciseId/submissions/all/students/$studentId",
                                ReqMethod.GET).await()

                if (!submissionResp.http200) {
                    errorMessage { Str.somethingWentWrong() }
                    error("Fetching all student submissions failed with status ${submissionResp.status}")
                }

                val submissionsWrap = submissionResp.parseTo(TeacherSubmissions.serializer()).await()

                data class SubData(val number: Int, val isLast: Boolean, val time: Date, val solution: String,
                                   val gradeAuto: Int?, val feedbackAuto: String?, val gradeTeacher: Int?, val feedbackTeacher: String?)

                val submissionIdMap = mutableMapOf<String, SubData>()
                var submissionNumber = submissionsWrap.count
                val submissions = submissionsWrap.submissions.map {

                    submissionIdMap[it.id] = SubData(submissionNumber, submissionNumber == submissionsWrap.count,
                            it.created_at, it.solution, it.grade_auto, it.feedback_auto, it.grade_teacher, it.feedback_teacher)

                    val submissionMap = mutableMapOf<String, Any?>(
                            "autoLabel" to Str.gradedAutomatically(),
                            "teacherLabel" to Str.gradedByTeacher(),
                            "missingLabel" to Str.notGradedYet(),
                            "id" to it.id,
                            "number" to submissionNumber--,
                            "time" to it.created_at.toEstonianString()
                    )

                    val validGrade = when {
                        it.grade_teacher != null -> {
                            submissionMap["points"] = it.grade_teacher.toString()
                            submissionMap["evalTeacher"] = true
                            it.grade_teacher
                        }
                        it.grade_auto != null -> {
                            submissionMap["points"] = it.grade_auto.toString()
                            submissionMap["evalAuto"] = true
                            it.grade_auto
                        }
                        else -> {
                            submissionMap["evalMissing"] = true
                            null
                        }
                    }

                    when {
                        validGrade == null -> Unit
                        validGrade >= threshold -> submissionMap["completed"] = true
                        else -> submissionMap["started"] = true
                    }

                    submissionMap.toJsObj()
                }.toTypedArray()

                val selectedSubId = getElemBySelector("[data-active-sub]")?.getAttribute("data-active-sub")
                debug { "Selected submission: $selectedSubId" }

                getElemById("all-submissions-section").innerHTML = tmRender("tm-teach-exercise-all-submissions", mapOf(
                        "submissions" to submissions
                ))

                if (selectedSubId != null) {
                    refreshSubListLinks(selectedSubId)
                } else {
                    warn { "Active submission id is null" }
                }

                getNodelistBySelector("[data-sub-id]").asList().forEach {
                    if (it is Element) {
                        val id = it.getAttribute("data-sub-id")
                                ?: error("No data-sub-id found on submission item")
                        val sub = submissionIdMap[id] ?: error("No submission $id found in idMap")

                        it.onVanillaClick(true) {
                            debug { "Painting submission $id" }
                            paintSubmission(id, sub.number, sub.time, sub.solution, sub.isLast,
                                    sub.gradeAuto, sub.feedbackAuto, sub.gradeTeacher, sub.feedbackTeacher )
                            refreshSubListLinks(id)
                        }
                    } else {
                        error("Submission item is not an Element")
                    }
                }

                Materialize.Tooltip.init(getNodelistBySelector(".tooltipped"))

            } else {
                // Box is visible at the moment
                debug { "Close all submissions" }
                getElemById("all-submissions-section").clear()
                getElemById("all-submissions-link").textContent = Str.allSubmissionsLink()
            }
        }


        getElemById("tab-student").textContent = "$givenName ${familyName[0]}"

        val tabs = Materialize.Tabs.getInstance(getElemById("tabs"))
        tabs.select("student")
        tabs.updateTabIndicator()

        MainScope().launch {
            val submissionResp =
                    fetchEms("/teacher/courses/$courseId/exercises/$courseExerciseId/submissions/all/students/$studentId?limit=1",
                            ReqMethod.GET).await()

            if (!submissionResp.http200) {
                errorMessage { Str.somethingWentWrong() }
                error("Fetching student submission failed with status ${submissionResp.status}")
            }

            val submissions = submissionResp.parseTo(TeacherSubmissions.serializer()).await()
            val submission = submissions.submissions[0]

            getElemById("student").innerHTML = tmRender("tm-teach-exercise-student-submission", emptyMap())
            getElemById("all-submissions-part").innerHTML = tmRender("tm-teach-exercise-student-submission-all", mapOf(
                    "allSubmissionsLink" to Str.allSubmissionsLink()
            ))
            paintSubmission(submission.id, submissions.count, submission.created_at, submission.solution, true,
                    submission.grade_auto, submission.feedback_auto, submission.grade_teacher, submission.feedback_teacher)

            if (isAllSubsOpen) {
                toggleSubmissionsBox()
                refreshSubListLinks(submission.id)
            }

            getElemById("all-submissions-link").onVanillaClick(true) { MainScope().launch { toggleSubmissionsBox() } }
        }
    }

    private fun refreshSubListLinks(selectedSubmissionid: String) {
        getNodelistBySelector("[data-sub-id]").asList().filterIsInstance<Element>().forEach {
            it.apply {
                setAttribute("href", "#!")
                removeClass("inactive")
            }
        }
        getElemBySelector("[data-sub-id='$selectedSubmissionid']")?.apply {
            removeAttribute("href")
            addClass("inactive")
        }
    }

    private fun renderTeacherAssessment(grade: Int, feedback: String?): String {
        return tmRender("tm-exercise-teacher-feedback", mapOf(
                "teacherLabel" to Str.teacherAssessmentLabel(),
                "teacherGradeLabel" to Str.teacherGradeLabel(),
                "grade" to grade.toString(),
                "feedback" to feedback
        ))
    }

    private fun renderAutoAssessment(grade: Int, feedback: String?): String {
        return tmRender("tm-exercise-auto-feedback", mapOf(
                "autoLabel" to Str.autoAssessmentLabel(),
                "autoGradeLabel" to Str.autoGradeLabel(),
                "grade" to grade.toString(),
                "feedback" to feedback
        ))
    }


    private fun buildStudentExercise(courseId: String, courseExerciseId: String) = MainScope().launch {

        suspend fun buildExerciseAndCrumbs() {
            val exercisePromise = fetchEms("/student/courses/$courseId/exercises/$courseExerciseId",
                    ReqMethod.GET)

            val courseTitle = BasicCourseInfo.get(courseId).await().title

            val exerciseResp = exercisePromise.await()
            if (!exerciseResp.http200) {
                errorMessage { Str.somethingWentWrong() }
                error("Fetching exercises failed with status ${exerciseResp.status}")
            }

            val exercise = exerciseResp.parseTo(StudentExercise.serializer()).await()

            getElemById("crumbs").innerHTML = tmRender("tm-exercise-crumbs", mapOf(
                    "coursesLabel" to Str.myCourses(),
                    "coursesHref" to "/courses",
                    "courseTitle" to courseTitle,
                    "courseHref" to "/courses/$courseId/exercises",
                    "exerciseTitle" to exercise.effective_title
            ))

            getElemById("exercise").innerHTML = tmRender("tm-stud-exercise-summary", mapOf(
                    "deadlineLabel" to Str.softDeadlineLabel(),
                    "deadline" to exercise.deadline?.toEstonianString(),
                    "graderTypeLabel" to Str.graderTypeLabel(),
                    "graderType" to if (exercise.grader_type == GraderType.AUTO) Str.graderTypeAuto() else Str.graderTypeTeacher(),
                    "thresholdLabel" to Str.thresholdLabel(),
                    "threshold" to exercise.threshold,
                    "title" to exercise.effective_title,
                    "text" to exercise.text_html
            ))
        }


        val fl = debugFunStart("buildStudentExercise")

        getContainer().innerHTML = tmRender("tm-stud-exercise", mapOf(
                "exerciseLabel" to Str.tabExerciseLabel(),
                "submitLabel" to Str.tabSubmitLabel()
        ))

        Materialize.Tabs.init(getElemById("tabs"))

        buildExerciseAndCrumbs()
        buildSubmit(courseId, courseExerciseId)
        fl?.end()
    }

    private suspend fun postSolution(courseId: String, courseExerciseId: String, solution: String) {
        debug { "Posting submission ${solution.substring(0, 15)}..." }
        val resp = fetchEms("/student/courses/$courseId/exercises/$courseExerciseId/submissions",
                ReqMethod.POST, mapOf("solution" to solution)).await()
        if (!resp.http200) {
            errorMessage { Str.somethingWentWrong() }
            error("Submitting failed with status ${resp.status}")
        }
        debug { "Submitted" }
    }

    private suspend fun buildSubmit(courseId: String, courseExerciseId: String, existingSubmission: StudentSubmission? = null) {
        if (existingSubmission != null) {
            debug { "Building submit tab using an existing submission" }
            paintSubmission(existingSubmission)
        } else {
            debug { "Building submit tab by fetching latest submission" }
            val resp = fetchEms("/student/courses/$courseId/exercises/$courseExerciseId/submissions/all?limit=1", ReqMethod.GET)
                    .await()

            if (!resp.http200) {
                errorMessage { Str.somethingWentWrong() }
                error("Fetching latest submission failed with status ${resp.status}")
            }

            val submissionsWrap = resp.parseTo(StudentSubmissions.serializer()).await()
            val submissions = submissionsWrap.submissions
            if (submissions.isEmpty()) {
                getElemById("submit").innerHTML = tmRender("tm-stud-exercise-submit", mapOf(
                        "checkLabel" to Str.submitAndCheckLabel()
                ))
            } else {
                val submission = submissions[0]
                paintSubmission(submission)
                if (submission.autograde_status == AutogradeStatus.IN_PROGRESS) {
                    paintAutoassInProgress()
                    pollForAutograde(courseId, courseExerciseId)
                }
            }
        }

        val editor = CodeMirror.fromTextArea(getElemById("submission"),
                objOf("mode" to "python",
                        "lineNumbers" to true,
                        "autoRefresh" to true,
                        "viewportMargin" to 100))

        getElemById("submit-button").onVanillaClick(true) {
            MainScope().launch {
                paintAutoassInProgress()
                postSolution(courseId, courseExerciseId, editor.getValue())
                pollForAutograde(courseId, courseExerciseId)
            }
        }
    }

    private fun paintAutoassInProgress() {
        val editorWrap = getElemById("submit-editor-wrap")
        val submitButton = getElemByIdAs<HTMLButtonElement>("submit-button")
        val editor = editorWrap.getElementsByClassName("CodeMirror")[0]?.CodeMirror
        submitButton.disabled = true
        submitButton.textContent = Str.autoAssessing()
        editor?.setOption("readOnly", true)
        editorWrap.addClass("no-cursor")
        getElemById("assessment-auto").innerHTML = tmRender("tm-exercise-auto-feedback", mapOf(
                "autoLabel" to Str.autoAssessmentLabel(),
                "autoGradeLabel" to Str.autoGradeLabel(),
                "grade" to "-",
                "feedback" to Str.autoAssessing()
        ))
    }

    private fun paintSubmission(submission: StudentSubmission) {
        getElemById("submit").innerHTML = tmRender("tm-stud-exercise-submit", mapOf(
                "timeLabel" to Str.lastSubmTimeLabel(),
                "time" to submission.submission_time.toEstonianString(),
                "solution" to submission.solution,
                "checkLabel" to Str.submitAndCheckLabel()
        ))
        if (submission.grade_auto != null) {
            getElemById("assessment-auto").innerHTML = renderAutoAssessment(submission.grade_auto, submission.feedback_auto)
        }
        if (submission.grade_teacher != null) {
            getElemById("assessment-teacher").innerHTML = renderTeacherAssessment(submission.grade_teacher, submission.feedback_teacher)
        }
    }

    private fun pollForAutograde(courseId: String, courseExerciseId: String) {
        debug { "Starting long poll for autoassessment" }
        fetchEms("/student/courses/$courseId/exercises/$courseExerciseId/submissions/latest/await", ReqMethod.GET)
                .then {
                    MainScope().launch {
                        if (!it.http200) {
                            errorMessage { Str.somethingWentWrong() }
                            error("Polling failed with status ${it.status}")
                        }
                        val submission = it.parseTo(StudentSubmission.serializer()).await()
                        debug { "Finished long poll, rebuilding" }
                        buildSubmit(courseId, courseExerciseId, submission)
                    }
                }
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