package pages

import AppProperties
import Auth
import DateSerializer
import Icons
import MathJax
import PageName
import PaginationConf
import Role
import Str
import UserMessageAction
import cache.BasicCourseInfo
import compareTo
import debug
import debugFunStart
import emptyToNull
import errorMessage
import getContainer
import getLastPageOffset
import highlightCode
import isNotNullAndTrue
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.dom.addClass
import kotlinx.dom.clear
import kotlinx.dom.removeClass
import kotlinx.serialization.Serializable
import libheaders.CodeMirror
import libheaders.Materialize
import libheaders.focus
import lightboxExerciseImages
import moveClass
import objOf
import observeValueChange
import onSingleClickWithDisabled
import org.w3c.dom.*
import pages.exercise.ExercisePage
import pages.sidenav.Sidenav
import queries.*
import rip.kspar.ezspa.*
import saveAsFile
import successMessage
import tmRender
import toEstonianString
import toJsObj
import warn
import kotlin.js.Date
import kotlin.math.min

object ExerciseSummaryPage : EasyPage() {

    private const val PAGE_STEP = AppProperties.SUBMISSIONS_ROWS_ON_PAGE

    @Serializable
    data class TeacherExercise(
            val exercise_id: String,
            val title: String,
            val title_alias: String?,
            val instructions_html: String?,
            val instructions_adoc: String?,
            val text_html: String?,
            val text_adoc: String?,
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
    data class Groups(
            val groups: List<Group>,
            val self_is_restricted: Boolean,
    )

    @Serializable
    data class Group(
            val id: String,
            val name: String
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
            val graded_by: GraderType?,
            val groups: String? = null
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

    @Serializable
    data class StudentDraft(
            val solution: String,
            @Serializable(with = DateSerializer::class)
            val created_at: Date
    )


    override val pageName: Any
        get() = PageName.EXERCISE_SUMMARY

    override val sidenavSpec: Sidenav.Spec
        get() = Sidenav.Spec(pathParams.courseId)

    override val pathSchema = "/courses/{courseId}/exercises/{courseExerciseId}/summary"

    data class PathParams(val courseId: String, val courseExerciseId: String)

    private val pathParams: PathParams
        get() = parsePathParams().let {
            PathParams(it["courseId"], it["courseExerciseId"])
        }

    override fun build(pageStateStr: String?) {
        super.build(pageStateStr)

        when (Auth.activeRole) {
            Role.STUDENT -> buildStudentExercise(pathParams.courseId, pathParams.courseExerciseId)
            Role.TEACHER, Role.ADMIN -> buildTeacherExercise(pathParams.courseId, pathParams.courseExerciseId, Auth.activeRole == Role.ADMIN)
        }
    }

    private fun buildTeacherExercise(courseId: String, courseExerciseId: String, isAdmin: Boolean) = MainScope().launch {
        val fl = debugFunStart("buildTeacherExercise")

        getContainer().innerHTML = tmRender("tm-teach-exercise", mapOf(
                "exerciseLabel" to Str.tabExerciseLabel(),
                "testingLabel" to Str.tabTestingLabel(),
                "studentSubmLabel" to Str.tabSubmissionsLabel()
        ))

        Materialize.Tabs.init(getElemById("tabs"))

        getElemById("exercise").innerHTML = tmRender("tm-loading-exercise")

        // Could be optimised to load exercise details & students in parallel,
        // requires passing an exercisePromise to buildStudents since the threshold is needed for painting
        val exerciseDetails = buildTeacherSummaryAndCrumbs(courseId, courseExerciseId, isAdmin)
        buildTeacherTesting(exerciseDetails.exercise_id)
        buildTeacherStudents(courseId, courseExerciseId, exerciseDetails.exercise_id, exerciseDetails.threshold)

        initTooltips()
        fl?.end()
    }

    private suspend fun buildTeacherSummaryAndCrumbs(courseId: String, courseExerciseId: String, isAdmin: Boolean): TeacherExercise {
        val fl = debugFunStart("buildTeacherSummaryAndCrumbs")

        val exercisePromise = fetchEms("/teacher/courses/$courseId/exercises/$courseExerciseId", ReqMethod.GET,
                successChecker = { http200 }, errorHandler = ErrorHandlers.noCourseAccessPage)

        val courseTitle = BasicCourseInfo.get(courseId).await().title
        val exercise = exercisePromise.await()
                .parseTo(TeacherExercise.serializer()).await()

        val effectiveTitle = exercise.title_alias ?: exercise.title

        Title.update {
            it.pageTitle = effectiveTitle
            it.parentPageTitle = courseTitle
        }

        if (isAdmin)
            Sidenav.replacePageSection(
                Sidenav.PageSection(
                    effectiveTitle, listOf(
                        Sidenav.Link(Icons.library, "Vaata ülesandekogus", ExercisePage.link(exercise.exercise_id))
                    )
                )
            )

        debug { "Exercise ID: ${exercise.exercise_id} (course exercise ID: $courseExerciseId, title: ${exercise.title}, title alias: ${exercise.title_alias})" }

        getElemById("crumbs").innerHTML = tmRender("tm-exercise-crumbs", mapOf(
                "coursesLabel" to Str.myCourses(),
                "coursesHref" to "/courses",
                "courseTitle" to courseTitle,
                "courseHref" to "/courses/$courseId/exercises",
                "exerciseTitle" to effectiveTitle
        ))

        val exerciseMap = mutableMapOf<String, Any?>(
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
                "exerciseTitle" to effectiveTitle,
                "exerciseText" to exercise.text_html
        )

        val aaFiles =
                if (exercise.grading_script != null) {
                    val assetFiles = exercise.assets ?: emptyList()
                    val aaFiles = listOf(AutoAsset("evaluate.sh", exercise.grading_script)) + assetFiles
                    exerciseMap["aaTitle"] = Str.aaTitle()
                    exerciseMap["aaFiles"] = aaFiles.mapIndexed { i, file ->
                        objOf("fileName" to file.file_name,
                                "fileIdx" to i)
                    }.toTypedArray()

                    aaFiles
                } else null

        getElemById("exercise").innerHTML = tmRender("tm-teach-exercise-summary", exerciseMap)

        lightboxExerciseImages()
        highlightCode()

        if (aaFiles != null) {
            initAaFileEditor(aaFiles)
        }

        MathJax.formatPageIfNeeded(exercise.instructions_html.orEmpty(), exercise.text_html.orEmpty())

        fl?.end()
        return exercise
    }

    private fun initAaFileEditor(aaFiles: List<AutoAsset>) {
        val docs = aaFiles.mapIndexed { i, file ->
            val mode = if (i == 0) "shell" else "python"
            CodeMirror.Doc(file.file_content, mode)
        }

        val editor = CodeMirror.fromTextArea(getElemById("aa-files"),
                objOf("mode" to "python",
                        "theme" to "idea",
                        "lineNumbers" to true,
                        "autoRefresh" to true,
                        "viewportMargin" to 100,
                        "readOnly" to true))

        CodeMirror.autoLoadMode(editor, "shell")

        val aaLinks = getElemsBySelector("a[data-file-idx]")

        aaLinks.map { link ->
            val fileIdx = link.getAttribute("data-file-idx")!!.toInt()
            link.onVanillaClick(true) {
                aaLinks.forEach {
                    it.removeClass("active")
                    it.setAttribute("href", "#!")
                }
                link.addClass("active")
                link.removeAttribute("href")
                editor.swapDoc(docs[fileIdx])
            }
        }

        (aaLinks[0] as HTMLAnchorElement).click()
    }


    private fun buildTeacherTesting(exerciseId: String) {

        suspend fun postSolution(solution: String): AutoassResult {
            debug { "Posting submission ${solution.substring(0, 15)}..." }
            val result = fetchEms("/exercises/$exerciseId/testing/autoassess",
                    ReqMethod.POST, mapOf("solution" to solution), successChecker = { http200 }).await()
                    .parseTo(AutoassResult.serializer()).await()
            debug { "Received result, grade: ${result.grade}" }
            return result
        }


        val fl = debugFunStart("buildTeacherTesting")
        getElemById("testing").innerHTML = tmRender("tm-teach-exercise-testing", mapOf(
                "checkLabel" to Str.doAutoAssess()
        ))
        val editor = CodeMirror.fromTextArea(getElemById("testing-submission"),
                objOf("mode" to "python",
                        "theme" to "idea",
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


    private suspend fun buildTeacherStudents(courseId: String, courseExerciseId: String, exerciseId: String, threshold: Int) {
        val fl = debugFunStart("buildTeacherStudents")
        getElemById("students").innerHTML = tmRender("tm-teach-exercise-students")
        val defaultGroupId = buildTeacherStudentsFrame(courseId, courseExerciseId, exerciseId, threshold)
        buildTeacherStudentsList(courseId, courseExerciseId, exerciseId, threshold, defaultGroupId)

        getElemByIdAs<HTMLButtonElement>("export-submissions-button").onSingleClickWithDisabled("Laen...") {
            debug { "Downloading submissions" }
            val selectedGroupId = getElemByIdAsOrNull<HTMLSelectElement>("group-select")?.value.emptyToNull()
            val groupsList = selectedGroupId?.let { listOf(mapOf("id" to it)) }
            val blob = fetchEms("/export/exercises/$exerciseId/submissions/latest", ReqMethod.POST,
                    mapOf("courses" to listOf(mapOf("id" to courseId, "groups" to groupsList))), successChecker = { http200 }).await()
                    .blob().await()
            val filename = "esitused-kursus-$courseId-ul-$courseExerciseId${selectedGroupId?.let { "-g-$it" }.orEmpty()}.zip"
            blob.saveAsFile(filename)
        }

        fl?.end()
    }

    private suspend fun buildTeacherStudentsFrame(courseId: String, courseExerciseId: String, exerciseId: String, threshold: Int): String? {
        val groups = fetchEms("/courses/$courseId/groups", ReqMethod.GET, successChecker = { http200 },
                errorHandler = ErrorHandlers.noCourseAccessPage).await()
                .parseTo(Groups.serializer()).await()
                .groups.sortedBy { it.name }

        debug { "Groups available: $groups" }

        getElemById("students-frame").innerHTML = tmRender("tm-teach-exercise-students-frame", mapOf(
                "exportSubmissionsLabel" to "Lae alla",
                "groupLabel" to if (groups.isNotEmpty()) "Rühm" else null,
                "allLabel" to "Kõik õpilased",
                "hasOneGroup" to (groups.size == 1),
                "groups" to groups.map { mapOf("id" to it.id, "name" to it.name) }))

        if (groups.isNotEmpty()) {
            initSelectFields()
            val groupSelect = getElemByIdAs<HTMLSelectElement>("group-select")
            groupSelect.onChange {
                MainScope().launch {
                    val group = groupSelect.value
                    debug { "Selected group $group" }
                    buildTeacherStudentsList(courseId, courseExerciseId, exerciseId, threshold, group)
                }
            }
        }

        return if (groups.size == 1) groups[0].id else null
    }

    private fun initSelectFields() {
        Materialize.FormSelect.init(getNodelistBySelector("select"), objOf("coverTrigger" to false))
    }

    private suspend fun buildTeacherStudentsList(courseId: String, courseExerciseId: String, exerciseId: String,
                                                 threshold: Int, groupId: String?, offset: Int = 0) {

        val q = createQueryString("group" to groupId, "limit" to PAGE_STEP.toString(), "offset" to offset.toString())
        val teacherStudents = fetchEms(
                "/teacher/courses/$courseId/exercises/$courseExerciseId/submissions/latest/students$q", ReqMethod.GET,
                successChecker = { http200 }, errorHandler = ErrorHandlers.noCourseAccessPage).await()
                .parseTo(TeacherStudents.serializer()).await()

        val studentArray = teacherStudents.students.map { student ->
            val studentMap = mutableMapOf<String, Any?>(
                    "id" to student.student_id,
                    "givenName" to student.given_name,
                    "familyName" to student.family_name,
                    "groups" to student.groups,
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


        val studentTotal = teacherStudents.student_count
        val paginationConf = if (studentTotal > PAGE_STEP) {
            PaginationConf(offset + 1, min(offset + PAGE_STEP, studentTotal), studentTotal,
                    offset != 0, offset + PAGE_STEP < studentTotal)
        } else null

        getElemById("students-list").innerHTML = tmRender("tm-teach-exercise-students-list", mapOf(
                "students" to studentArray,
                "autoLabel" to Str.gradedAutomatically(),
                "teacherLabel" to Str.gradedByTeacher(),
                "missingLabel" to Str.notGradedYet(),
                "hasPagination" to (paginationConf != null),
                "pageStart" to paginationConf?.pageStart,
                "pageEnd" to paginationConf?.pageEnd,
                "pageTotal" to paginationConf?.pageTotal,
                "pageTotalLabel" to ", kokku ",
                "canGoBack" to paginationConf?.canGoBack,
                "canGoForward" to paginationConf?.canGoForward
        ))

        if (paginationConf?.canGoBack.isNotNullAndTrue) {
            getElemsByClass("go-first").onVanillaClick(true) {
                buildTeacherStudentsList(courseId, courseExerciseId, exerciseId, threshold, groupId, 0)
            }
            getElemsByClass("go-back").onVanillaClick(true) {
                buildTeacherStudentsList(courseId, courseExerciseId, exerciseId, threshold, groupId, offset - PAGE_STEP)
            }
        }

        if (paginationConf?.canGoForward.isNotNullAndTrue) {
            getElemsByClass("go-forward").onVanillaClick(true) {
                buildTeacherStudentsList(courseId, courseExerciseId, exerciseId, threshold, groupId, offset + PAGE_STEP)
            }
            getElemsByClass("go-last").onVanillaClick(true) {
                buildTeacherStudentsList(courseId, courseExerciseId, exerciseId, threshold, groupId, getLastPageOffset(studentTotal, PAGE_STEP))
            }
        }

        getElemsBySelector("[data-student-id]").forEach {
            val id = it.getAttribute("data-student-id")
                    ?: error("No data-student-id found on student item")
            val givenName = it.getAttribute("data-given-name")
                    ?: error("No data-given-name found on student item")
            val familyName = it.getAttribute("data-family-name")
                    ?: error("No data-family-name found on student item")

            it.onVanillaClick(true) {
                buildStudentTab(courseId, courseExerciseId, exerciseId, threshold, id, givenName, familyName, false)
            }
        }

        initTooltips()
    }


    private fun buildStudentTab(courseId: String, courseExerciseId: String, exerciseId: String, threshold: Int,
                                studentId: String, givenName: String, familyName: String, isAllSubsOpen: Boolean) {

        suspend fun addAssessment(grade: Int, feedback: String, submissionId: String) {
            val assMap: MutableMap<String, Any> = mutableMapOf("grade" to grade)
            if (feedback.isNotBlank())
                assMap["feedback"] = feedback

            debug { "Posting assessment $assMap" }

            fetchEms("/teacher/courses/$courseId/exercises/$courseExerciseId/submissions/$submissionId/assessments",
                    ReqMethod.POST, assMap, successChecker = { http200 }).await()
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
                        successMessage { Str.assessmentAddedMsg() }
                        buildStudentTab(courseId, courseExerciseId, exerciseId, threshold, studentId, givenName, familyName, isSubmissionBoxVisible())
                        buildTeacherStudents(courseId, courseExerciseId, exerciseId, threshold)
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
                    "theme" to "idea",
                    "lineNumbers" to true,
                    "autoRefresh" to true,
                    "viewportMargin" to 100,
                    "readOnly" to true))

            getElemByIdOrNull("add-grade-link")?.onVanillaClick(true) { toggleAddGradeBox(id) }

            getElemByIdOrNull("last-submission-link")?.onVanillaClick(true) {
                val isAllSubsBoxOpen = getElemByIdOrNull("all-submissions-wrap") != null
                buildStudentTab(courseId, courseExerciseId, exerciseId, threshold, studentId, givenName, familyName, isAllSubsBoxOpen)
            }
        }

        suspend fun paintSubmissionBox() {
            getElemById("all-submissions-section").innerHTML = tmRender("tm-teach-exercise-all-submissions-placeholder", mapOf(
                    "text" to Str.loadingAllSubmissions()
            ))

            val submissionsWrap =
                    fetchEms("/teacher/courses/$courseId/exercises/$courseExerciseId/submissions/all/students/$studentId",
                            ReqMethod.GET, successChecker = { http200 }).await()
                            .parseTo(TeacherSubmissions.serializer()).await()

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
                                sub.gradeAuto, sub.feedbackAuto, sub.gradeTeacher, sub.feedbackTeacher)
                        refreshSubListLinks(id)
                    }
                } else {
                    error("Submission item is not an Element")
                }
            }

            initTooltips()
        }

        suspend fun toggleSubmissionsBox() {
            if (!isSubmissionBoxVisible()) {
                debug { "Open all submissions" }
                getElemById("all-submissions-link").textContent = Str.closeToggleLink()
                paintSubmissionBox()
            } else {
                debug { "Close all submissions" }
                getElemById("all-submissions-section").clear()
                getElemById("all-submissions-link").textContent = Str.allSubmissionsLink()
            }
        }


        val studentTab = getElemById("tab-student")
        studentTab.removeClass("display-none")
        getElemById("student").clear()
        val studentTabLink = studentTab.firstElementChild
        studentTabLink?.textContent = "$givenName ${familyName[0]}"

        val tabs = Materialize.Tabs.getInstance(getElemById("tabs"))!!
        tabs.select("student")
        tabs.updateTabIndicator()
        studentTabLink?.focus()

        MainScope().launch {
            val submissions =
                    fetchEms("/teacher/courses/$courseId/exercises/$courseExerciseId/submissions/all/students/$studentId?limit=1",
                            ReqMethod.GET, successChecker = { http200 }).await()
                            .parseTo(TeacherSubmissions.serializer()).await()

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

    private fun isSubmissionBoxVisible() = getElemByIdOrNull("all-submissions-wrap") != null

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

        fun buildExerciseAndCrumbs() = MainScope().launch {
            val exercisePromise = fetchEms(
                "/student/courses/$courseId/exercises/$courseExerciseId", ReqMethod.GET,
                successChecker = { http200 },
                errorHandlers = listOf(ErrorHandlers.noCourseAccessPage, ErrorHandlers.noVisibleExerciseMsg)
            )

            val courseTitle = BasicCourseInfo.get(courseId).await().title
            val exercise = exercisePromise.await().parseTo(StudentExercise.serializer()).await()

            Title.update {
                it.pageTitle = exercise.effective_title
                it.parentPageTitle = courseTitle
            }

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
            lightboxExerciseImages()
            highlightCode()
            MathJax.formatPageIfNeeded(exercise.text_html.orEmpty())
        }


        val fl = debugFunStart("buildStudentExercise")

        getContainer().innerHTML = tmRender("tm-stud-exercise", mapOf(
                "exerciseLabel" to Str.tabExerciseLabel(),
                "submitLabel" to Str.tabSubmitLabel()
        ))

        Materialize.Tabs.init(getElemById("tabs"))

        getElemById("exercise").innerHTML = tmRender("tm-loading-exercise")
        getElemById("submit").innerHTML = tmRender("tm-loading-submission")

        buildExerciseAndCrumbs()
        buildSubmit(courseId, courseExerciseId)
        fl?.end()
    }

    private suspend fun postSolution(courseId: String, courseExerciseId: String, solution: String) {
        debug { "Posting submission ${solution.substring(0, 15)}..." }
        fetchEms("/student/courses/$courseId/exercises/$courseExerciseId/submissions", ReqMethod.POST,
                mapOf("solution" to solution), successChecker = { http200 },
            errorHandlers = listOf(ErrorHandlers.noCourseAccessPage, ErrorHandlers.noVisibleExerciseMsg)
        ).await()
        debug { "Submitted" }
        successMessage { Str.submitSuccessMsg() }
    }

    private fun buildSubmit(courseId: String, courseExerciseId: String, existingSubmission: StudentSubmission? = null) = MainScope().launch {

        suspend fun saveSubmissionDraft(solution: String) {
            debug { "Saving submission draft" }
            paintSyncLoading()
            fetchEms("/student/courses/$courseId/exercises/$courseExerciseId/draft", ReqMethod.POST,
                    mapOf("solution" to solution), successChecker = { http200 },
                    errorHandler = {
                        handleAlways {
                            warn { "Failed to save draft with status $status" }
                            // TODO: allow trying again - recursive call in message action fails due to a compiler bug
                            errorMessage { "Mustandi salvestamine ebaõnnestus" }
                            paintSyncFail()
                        }
                    }).await()
            debug { "Draft saved" }
            paintSyncDone()
        }


        val latestSubmissionSolution: String?

        if (existingSubmission != null) {
            debug { "Building submit tab using an existing submission" }
            paintSubmission(existingSubmission, null)
            latestSubmissionSolution = existingSubmission.solution
        } else {
            debug { "Building submit tab by fetching latest submission" }
            val draftPromise = fetchEms("/student/courses/$courseId/exercises/$courseExerciseId/draft", ReqMethod.GET,
                    successChecker = { http200 or http204 },
                    errorHandlers = listOf(ErrorHandlers.noCourseAccessPage, ErrorHandlers.noVisibleExerciseMsg))

            val submission = fetchEms("/student/courses/$courseId/exercises/$courseExerciseId/submissions/all?limit=1", ReqMethod.GET,
                    successChecker = { http200 },
                    errorHandlers = listOf(ErrorHandlers.noCourseAccessPage, ErrorHandlers.noVisibleExerciseMsg)
            ).await()
                .parseTo(StudentSubmissions.serializer()).await()
                .submissions.getOrNull(0)

            val draftResp = draftPromise.await()
            val draft = if (draftResp.http200) draftResp.parseTo(StudentDraft.serializer()).await() else null

            paintSubmission(submission, draft)
            latestSubmissionSolution = submission?.solution

            if (submission?.autograde_status == AutogradeStatus.IN_PROGRESS) {
                disableEditSubmit()
                paintAutoassInProgress()
                pollForAutograde(courseId, courseExerciseId)
            }
        }

        val editor = CodeMirror.fromTextArea(getElemById("submission"),
                objOf("mode" to "python",
                        "theme" to "idea",
                        "lineNumbers" to true,
                        "autoRefresh" to true,
                        "viewportMargin" to 100))

        getElemById("submit-button").onVanillaClick(true) {
            MainScope().launch {
                disableEditSubmit()
                postSolution(courseId, courseExerciseId, editor.getValue())
                paintAutoassInProgress()
                pollForAutograde(courseId, courseExerciseId)
            }
        }

        paintSyncDone()
        MainScope().launch {
            observeValueChange(3000, 1000,
                    valueProvider = { editor.getValue() },
                    action = {
                        saveSubmissionDraft(it)
                        paintDraft(it, latestSubmissionSolution)
                    },
                    continuationConditionProvider = { getElemByIdOrNull("submission") != null },
                    idleCallback = {
                        paintSyncUnsynced()
                    })
        }
    }

    private fun disableEditSubmit() {
        val editorWrap = getElemById("submit-editor-wrap")
        val submitButton = getElemByIdAs<HTMLButtonElement>("submit-button")
        val editor = editorWrap.getElementsByClassName("CodeMirror")[0]?.CodeMirror
        submitButton.disabled = true
        submitButton.textContent = Str.autoAssessing()
        editor?.setOption("readOnly", true)
        editorWrap.addClass("editor-read-only")
    }

    private fun paintAutoassInProgress() {
        getElemById("assessment-auto").innerHTML = tmRender("tm-exercise-auto-feedback", mapOf(
                "autoLabel" to Str.autoAssessmentLabel(),
                "autoGradeLabel" to Str.autoGradeLabel(),
                "grade" to "-",
                "feedback" to Str.autoAssessing()
        ))
    }

    private fun paintSubmission(submission: StudentSubmission?, draft: StudentDraft?) {
        getElemById("submit").innerHTML = tmRender("tm-stud-exercise-submit", mapOf(
                "timeLabel" to Str.lastSubmTimeLabel(),
                "checkLabel" to Str.submitAndCheckLabel(),
                "doneLabel" to "Mustand salvestatud",
                "undoneLabel" to "Mustand salvestamata",
                "failLabel" to "Mustandi salvestamine ebaõnnestus",
                "syncingLabel" to "Salvestan mustandit...",
                "restoreLabel" to "Taasta viimane esitus",
                "time" to submission?.submission_time?.toEstonianString()
        ))
        initTooltips()

        if (submission?.grade_auto != null) {
            getElemById("assessment-auto").innerHTML = renderAutoAssessment(submission.grade_auto, submission.feedback_auto)
        }
        if (submission?.grade_teacher != null) {
            getElemById("assessment-teacher").innerHTML = renderTeacherAssessment(submission.grade_teacher, submission.feedback_teacher)
        }

        when {
            submission == null && draft != null -> {
                paintDraft(draft.solution, null)
            }
            submission != null && draft == null -> {
                paintLatestSubmission(submission.solution)
            }
            submission != null && draft != null -> {
                when {
                    submission.solution == draft.solution || submission.submission_time >= draft.created_at -> {
                        paintLatestSubmission(submission.solution)
                    }
                    else -> {
                        paintDraft(draft.solution, submission.solution)
                    }
                }
            }
        }
    }

    private fun paintDraft(solution: String, submissionSolution: String?) {
        getElemById("submission").textContent = solution
        paintDraftState(solution, submissionSolution)
    }

    private fun paintDraftState(draftSolution: String, submissionSolution: String?) {
        getElemById("editor-top-text").textContent = "Mustand (esitamata)"
        if (submissionSolution != null && draftSolution != submissionSolution) {
            val restoreBtn = getElemById("restore-latest-sub-btn")
            restoreBtn.addClass("visible")
            restoreBtn.onVanillaClick(true) {
                debug { "Restoring latest sub" }
                paintLatestSubmission(submissionSolution)
            }
        }
    }

    private fun paintLatestSubmission(solution: String) {
        getElemById("submission").textContent = solution
        getElemById("submit-editor-wrap").getElementsByClassName("CodeMirror")[0]?.CodeMirror?.setValue(solution)
        paintLatestSubmissionState()
    }

    private fun paintLatestSubmissionState() {
        getElemById("editor-top-text").textContent = "Viimane esitus"
        getElemById("restore-latest-sub-btn").removeClass("visible")
    }

    private fun pollForAutograde(courseId: String, courseExerciseId: String) {
        debug { "Starting long poll for autoassessment" }
        fetchEms(
            "/student/courses/$courseId/exercises/$courseExerciseId/submissions/latest/await", ReqMethod.GET,
            successChecker = { http200 },
            errorHandlers = listOf(ErrorHandlers.noCourseAccessPage, ErrorHandlers.noVisibleExerciseMsg)
        ).then {
            it.parseTo(StudentSubmission.serializer())
        }.then {
            debug { "Finished long poll, rebuilding" }
            buildSubmit(courseId, courseExerciseId, it)
        }
    }

    private fun paintSyncDone() {
        moveClass(getElemsBySelector("#sync-indicator .icon"), getElemById("sync-done"), "visible")
    }

    private fun paintSyncUnsynced() {
        moveClass(getElemsBySelector("#sync-indicator .icon"), getElemById("sync-undone"), "visible")
    }

    private fun paintSyncLoading() {
        moveClass(getElemsBySelector("#sync-indicator .icon"), getElemById("sync-loading"), "visible")
    }

    private fun paintSyncFail() {
        moveClass(getElemsBySelector("#sync-indicator .icon"), getElemById("sync-fail"), "visible")
    }

    private fun initTooltips() {
        Materialize.Tooltip.init(getNodelistBySelector(".tooltipped"))
    }

    fun link(courseId: String, courseExerciseId: String) =
        constructPathLink(mapOf("courseId" to courseId, "courseExerciseId" to courseExerciseId))
}
