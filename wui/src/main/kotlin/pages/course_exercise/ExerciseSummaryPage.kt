package pages.course_exercise

import AppProperties
import Auth
import DateSerializer
import EzDate
import Icons
import Key
import LocalStore
import MathJax
import PageName
import PaginationConf
import Role
import cache.BasicCourseInfo
import compareTo
import dao.CourseExercisesStudentDAO
import dao.CourseExercisesTeacherDAO
import dao.ExerciseDAO
import debug
import debugFunStart
import emptyToNull
import getContainer
import getLastPageOffset
import highlightCode
import isNotNullAndTrue
import kotlinx.browser.window
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
import libheaders.tabHandler
import onSingleClickWithDisabled
import org.w3c.dom.*
import pages.EasyPage
import pages.Title
import pages.course_exercise.student.StudentCourseExerciseComp
import pages.course_exercise.teacher.TeacherCourseExerciseComp
import pages.course_exercises_list.UpdateCourseExerciseModalComp
import pages.exercise_in_library.ExercisePage
import pages.exercise_in_library.TestingTabComp
import pages.sidenav.ActivePage
import pages.sidenav.Sidenav
import queries.*
import restore
import rip.kspar.ezspa.*
import saveAsFile
import successMessage
import tmRender
import translation.Str
import warn
import kotlin.js.Date
import kotlin.math.min

object ExerciseSummaryPage : EasyPage() {

    private const val PAGE_STEP = AppProperties.SUBMISSIONS_ROWS_ON_PAGE


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
        val graded_by: ExerciseDAO.GraderType?,
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


    private var rootComp: Component? = null

    override val pageName: Any
        get() = PageName.EXERCISE_SUMMARY

    override val sidenavSpec: Sidenav.Spec
        get() = Sidenav.Spec(pathParams.courseId, ActivePage.STUDENT_EXERCISE)

    override val pathSchema = "/courses/{courseId}/exercises/{courseExerciseId}/**"

    data class PathParams(val courseId: String, val courseExerciseId: String)

    private val pathParams: PathParams
        get() = parsePathParams().let {
            PathParams(it["courseId"], it["courseExerciseId"])
        }

    override val courseId
        get() = pathParams.courseId

    val courseExerciseId
        get() = pathParams.courseExerciseId

    override fun build(pageStateStr: String?) {
        super.build(pageStateStr)
        val scrollPosition = pageStateStr.getScrollPosFromState()

        doInPromise {
            getHtml().addClass("wui3", "full-width")

            rootComp = when (Auth.activeRole) {
                Role.STUDENT -> {
                    Sidenav.refresh(sidenavSpec, true)
                    StudentCourseExerciseComp(courseId, courseExerciseId, ::setWildcardPath)

                }

                Role.TEACHER, Role.ADMIN -> {
                    TeacherCourseExerciseComp(
                        courseId,
                        courseExerciseId,
                        getCurrentQueryParamValue("tab"),
                        ::setWildcardPath
                    )
                }
            }

            rootComp!!.createAndBuild().await()
            scrollPosition?.restore()
            Navigation.catchNavigation {
                rootComp!!.hasUnsavedChanges()
            }
        }
    }

    override fun onPreNavigation() {
        updateStateWithScrollPos()
    }

    override fun destruct() {
        super.destruct()
        rootComp?.destroy()
        rootComp = null
        Navigation.stopNavigationCatching()
        getHtml().removeClass("wui3", "full-width")
    }

    private fun setWildcardPath(wildcardPathSuffix: String) {
        updateUrl(link(courseId, courseExerciseId) + wildcardPathSuffix + window.location.search)
    }

    // Bit of a hack until we migrate this page
    private val updateModalDst = IdGenerator.nextId()
    private val feedbackDstTesting = IdGenerator.nextId()
    private val feedbackDstSub = IdGenerator.nextId()

    private var backAbort = AbortController()

    private fun buildTeacherExercise(courseId: String, courseExerciseId: String, isAdmin: Boolean) =
        MainScope().launch {
            val fl = debugFunStart("buildTeacherExercise")

            getContainer().innerHTML = tmRender(
                "tm-teach-exercise", mapOf(
                    "exerciseLabel" to Str.tabExerciseLabel,
                    "testingLabel" to Str.tabTestingLabel,
                    "studentSubmLabel" to Str.tabSubmissionsLabel
                )
            )

            val tabs = Materialize.Tabs.init(getElemById("tabs"))

            getElemById("exercise").innerHTML = tmRender("tm-loading-exercise")

            // Could be optimised to load exercise details & students in parallel,
            // requires passing an exercisePromise to buildStudents since the threshold is needed for painting
            val exerciseDetails = buildTeacherSummaryAndCrumbs(courseId, courseExerciseId, isAdmin)
            if (exerciseDetails.grader_type == ExerciseDAO.GraderType.AUTO)
                buildTeacherTesting(courseId, exerciseDetails.exercise_id)
            buildTeacherStudents(
                courseId,
                courseExerciseId,
                exerciseDetails.exercise_id,
                exerciseDetails.threshold,
                exerciseDetails.soft_deadline
            )

            initTooltips()

            tabs.updateTabIndicator()
            fl?.end()
        }

    private suspend fun buildTeacherSummaryAndCrumbs(
        courseId: String,
        courseExerciseId: String,
        isAdmin: Boolean
    ): CourseExercisesTeacherDAO.TeacherCourseExerciseDetails {
        val fl = debugFunStart("buildTeacherSummaryAndCrumbs")

        val exercisePromise = fetchEms(
            "/teacher/courses/$courseId/exercises/$courseExerciseId", ReqMethod.GET,
            successChecker = { http200 }, errorHandler = ErrorHandlers.noCourseAccessMsg
        )

        val courseTitle = BasicCourseInfo.get(courseId).await().effectiveTitle
        val exercise = exercisePromise.await()
            .parseTo(CourseExercisesTeacherDAO.TeacherCourseExerciseDetails.serializer()).await()

        val effectiveTitle = exercise.title_alias ?: exercise.title

        Title.update {
            it.pageTitle = effectiveTitle
            it.parentPageTitle = courseTitle
        }

        debug { "Exercise ID: ${exercise.exercise_id} (course exercise ID: $courseExerciseId, title: ${exercise.title}, title alias: ${exercise.title_alias})" }

        getElemById("crumbs").innerHTML = tmRender(
            "tm-exercise-crumbs", mapOf(
                "coursesLabel" to Str.myCourses,
                "coursesHref" to "/courses",
                "courseTitle" to courseTitle,
                "courseHref" to "/courses/$courseId/exercises",
                "exerciseTitle" to effectiveTitle
            )
        )

        val exerciseMap = mutableMapOf<String, Any?>(
            "softDeadlineLabel" to Str.softDeadlineLabel,
            "hardDeadlineLabel" to Str.hardDeadlineLabel,
            "studentVisibleFromTimeLabel" to Str.studentVisibleFromTimeLabel,
            "softDeadline" to exercise.soft_deadline?.let { EzDate(it).toHumanString(EzDate.Format.FULL) },
            "hardDeadline" to exercise.hard_deadline?.let { EzDate(it).toHumanString(EzDate.Format.FULL) },
            "studentVisibleFromTime" to if (!exercise.student_visible) exercise.student_visible_from?.let {
                EzDate(it).toHumanString(EzDate.Format.FULL)
            } else null,
            "exerciseTitle" to effectiveTitle,
            "exerciseText" to exercise.text_html,
            "updateModalDst" to updateModalDst,
        )

        val aaFiles =
            if (exercise.grading_script != null) {
                val assetFiles = exercise.assets ?: emptyList()
                val aaFiles =
                    listOf(CourseExercisesTeacherDAO.AutoAsset("evaluate.sh", exercise.grading_script)) + assetFiles
                exerciseMap["aaTitle"] = Str.aaTitle
                exerciseMap["aaFiles"] = aaFiles.mapIndexed { i, file ->
                    objOf(
                        "fileName" to file.file_name,
                        "fileIdx" to i
                    )
                }.toTypedArray()

                aaFiles
            } else null

        getElemById("exercise").innerHTML = tmRender("tm-teach-exercise-summary", exerciseMap)


        Sidenav.replacePageSection(
            Sidenav.PageSection(
                effectiveTitle,
                buildList {
                    add(
                        Sidenav.Action(Icons.settings, Str.exerciseSettings) {
                            val m = UpdateCourseExerciseModalComp(
                                courseId,
                                UpdateCourseExerciseModalComp.CourseExercise(
                                    courseExerciseId,
                                    exercise.title,
                                    exercise.title_alias,
                                    exercise.threshold,
                                    exercise.student_visible,
                                    exercise.student_visible_from?.let { EzDate(it) },
                                    exercise.soft_deadline?.let { EzDate(it) },
                                    exercise.hard_deadline?.let { EzDate(it) },
                                ),
                                null,
                                dstId = updateModalDst
                            )

                            m.createAndBuild().await()
                            val modalReturn = m.openWithClosePromise().await()
                            m.destroy()
                            if (modalReturn != null) {
                                build(null)
                            }
                        }
                    )
                    if (exercise.has_lib_access)
                        add(Sidenav.Link(Icons.library, Str.openInLib, ExercisePage.link(exercise.exercise_id)))
                }
            )
        )


        highlightCode()

        if (aaFiles != null) {
            initAaFileEditor(aaFiles)
        }

        MathJax.formatPageIfNeeded(exercise.instructions_html.orEmpty(), exercise.text_html.orEmpty())

        fl?.end()
        return exercise
    }

    private fun initAaFileEditor(aaFiles: List<CourseExercisesTeacherDAO.AutoAsset>) {
        val docs = aaFiles.mapIndexed { i, file ->
            val mode = if (i == 0) "shell" else "python"
            CodeMirror.Doc(file.file_content, mode)
        }

        val editor = CodeMirror.fromTextArea(
            getElemById("aa-files"),
            objOf(
                "mode" to "python",
                "theme" to "idea",
                "lineNumbers" to true,
                "autoRefresh" to true,
                "viewportMargin" to 100,
                "readOnly" to true
            )
        )

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


    private suspend fun buildTeacherTesting(courseId: String, exerciseId: String) {

        suspend fun postSolution(solution: String): AutoassResult {
            debug { "Posting submission ${solution.substring(0, 15)}..." }
            val result = fetchEms("/exercises/$exerciseId/testing/autoassess" + createQueryString("course" to courseId),
                ReqMethod.POST, mapOf("solution" to solution), successChecker = { http200 }).await()
                .parseTo(AutoassResult.serializer()).await()
            debug { "Received result, grade: ${result.grade}" }
            return result
        }


        val fl = debugFunStart("buildTeacherTesting")

        val latestSubmission =
            fetchEms("/exercises/$exerciseId/testing/autoassess/submissions${createQueryString("limit" to "1")}",
                ReqMethod.GET,
                successChecker = { http200 }
            ).await().parseTo(TestingTabComp.LatestSubmissions.serializer()).await()
                .submissions.getOrNull(0)?.solution

        getElemById("testing").innerHTML = tmRender(
            "tm-teach-exercise-testing", mapOf(
                "latestSubmission" to latestSubmission,
                "checkLabel" to Str.doAutoAssess,
            )
        )
        val editor = CodeMirror.fromTextArea(
            getElemById("testing-submission"),
            objOf(
                "mode" to "python",
                "theme" to "idea",
                "lineNumbers" to true,
                "autoRefresh" to true,
                "viewportMargin" to 100,
                "indentUnit" to 4,
                "matchBrackets" to true,
                "extraKeys" to tabHandler,
                "placeholder" to Str.solutionEditorPlaceholder,
            )
        )

        val submitButton = getElemByIdAs<HTMLButtonElement>("testing-submit")

        submitButton.onVanillaClick(true) {
            MainScope().launch {
                try {
                    submitButton.disabled = true
                    submitButton.textContent = Str.autoAssessing
                    editor.setOption("readOnly", true)
                    val autoAssessmentWrap = getElemById("testing-assessment")
                    autoAssessmentWrap.innerHTML = """<ez-dst id='$feedbackDstTesting'></ez-dst>"""
                    renderAutoLoaderTesting()
                    val solution = editor.getValue()
                    val result = postSolution(solution)
                    autoAssessmentWrap.innerHTML = """<ez-dst id='$feedbackDstTesting'></ez-dst>"""
                    renderAutoAssessmentTesting(result.grade, result.feedback, false)
                } finally {
                    editor.setOption("readOnly", false)
                    submitButton.textContent = Str.doAutoAssess
                    submitButton.disabled = false
                }
            }
        }
        fl?.end()
    }


    private suspend fun buildTeacherStudents(
        courseId: String,
        courseExerciseId: String,
        exerciseId: String,
        threshold: Int,
        deadline: Date?,
    ) {
        val fl = debugFunStart("buildTeacherStudents")
        getElemById("students").innerHTML = tmRender("tm-teach-exercise-students")
        val defaultGroupId = buildTeacherStudentsFrame(courseId, courseExerciseId, exerciseId, threshold, deadline)
        buildTeacherStudentsList(courseId, courseExerciseId, exerciseId, threshold, deadline, defaultGroupId)

        getElemByIdAs<HTMLButtonElement>("export-submissions-button").onSingleClickWithDisabled(Str.downloading) {
            debug { "Downloading submissions" }
            val selectedGroupId = getElemByIdAsOrNull<HTMLSelectElement>("group-select")?.value.emptyToNull()
            val groupsList = selectedGroupId?.let { listOf(mapOf("id" to it)) }
            val blob = fetchEms("/export/exercises/$exerciseId/submissions/latest",
                ReqMethod.POST,
                mapOf("courses" to listOf(mapOf("id" to courseId, "groups" to groupsList))),
                successChecker = { http200 }).await()
                .blob().await()
            val filename =
                "esitused-kursus-$courseId-ul-$courseExerciseId${selectedGroupId?.let { "-g-$it" }.orEmpty()}.zip"
            blob.saveAsFile(filename)
        }

        fl?.end()
    }

    private suspend fun buildTeacherStudentsFrame(
        courseId: String,
        courseExerciseId: String,
        exerciseId: String,
        threshold: Int,
        deadline: Date?
    ): String? {
        val groups = fetchEms(
            "/courses/$courseId/groups", ReqMethod.GET, successChecker = { http200 },
            errorHandler = ErrorHandlers.noCourseAccessMsg
        ).await()
            .parseTo(Groups.serializer()).await()
            .groups.sortedBy { it.name }

        debug { "Groups available: $groups" }
        val preselectedGroupId = LocalStore.get(Key.TEACHER_SELECTED_GROUP).let {
            if (groups.map { it.id }.contains(it)) it else null
        }
        debug { "Preselected group id: $preselectedGroupId" }

        getElemById("students-frame").innerHTML = tmRender(
            "tm-teach-exercise-students-frame", mapOf(
                "exportSubmissionsLabel" to Str.doDownload,
                "groupLabel" to if (groups.isNotEmpty()) Str.accountGroup else null,
                "allLabel" to Str.allStudents,
                "groups" to groups.map {
                    mapOf(
                        "id" to it.id,
                        "name" to it.name,
                        "selected" to (it.id == preselectedGroupId),
                    )
                }
            )
        )

        if (groups.isNotEmpty()) {
            initSelectFields()
            val groupSelect = getElemByIdAs<HTMLSelectElement>("group-select")
            groupSelect.onChange {
                MainScope().launch {
                    val groupId = groupSelect.value
                    debug { "Selected group $groupId" }
                    LocalStore.set(Key.TEACHER_SELECTED_GROUP, groupId.emptyToNull())
                    buildTeacherStudentsList(courseId, courseExerciseId, exerciseId, threshold, deadline, groupId)
                }
            }
        }

        return preselectedGroupId
    }

    private fun initSelectFields() {
        Materialize.FormSelect.init(getNodelistBySelector("select"), objOf("coverTrigger" to false))
    }

    private suspend fun buildTeacherStudentsList(
        courseId: String, courseExerciseId: String, exerciseId: String,
        threshold: Int, deadline: Date?, groupId: String?, offset: Int = 0
    ) {

        val q = createQueryString("group" to groupId, "limit" to PAGE_STEP.toString(), "offset" to offset.toString())
        val teacherStudents = fetchEms(
            "/teacher/courses/$courseId/exercises/$courseExerciseId/submissions/latest/students$q", ReqMethod.GET,
            successChecker = { http200 }, errorHandler = ErrorHandlers.noCourseAccessMsg
        ).await()
            .parseTo(TeacherStudents.serializer()).await()

        val studentArray = teacherStudents.students.map { student ->
            val studentMap = mutableMapOf<String, Any?>(
                "id" to student.student_id,
                "givenName" to student.given_name,
                "familyName" to student.family_name,
                "groups" to student.groups,
                "deadlineIcon" to if (
                    student.submission_time != null &&
                    deadline != null &&
                    deadline < student.submission_time
                ) Icons.alarmClock else null,
                "time" to student.submission_time?.let { EzDate(it).toHumanString(EzDate.Format.FULL) },
                "points" to student.grade?.toString()
            )

            when (student.graded_by) {
                ExerciseDAO.GraderType.AUTO -> {
                    studentMap["evalAuto"] = true
                }

                ExerciseDAO.GraderType.TEACHER -> {
                    studentMap["evalTeacher"] = true
                }

                null -> {}
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
            PaginationConf(
                offset + 1, min(offset + PAGE_STEP, studentTotal), studentTotal,
                offset != 0, offset + PAGE_STEP < studentTotal
            )
        } else null

        getElemById("students-list").innerHTML = tmRender(
            "tm-teach-exercise-students-list", mapOf(
                "students" to studentArray,
                "autoLabel" to Str.gradedAutomatically,
                "teacherLabel" to Str.gradedByTeacher,
                "missingLabel" to Str.notGradedYet,
                "hasPagination" to (paginationConf != null),
                "pageStart" to paginationConf?.pageStart,
                "pageEnd" to paginationConf?.pageEnd,
                "pageTotal" to paginationConf?.pageTotal,
                "pageTotalLabel" to ", ${Str.total} ",
                "canGoBack" to paginationConf?.canGoBack,
                "canGoForward" to paginationConf?.canGoForward
            )
        )

        if (paginationConf?.canGoBack.isNotNullAndTrue) {
            getElemsByClass("go-first").onVanillaClick(true) {
                buildTeacherStudentsList(courseId, courseExerciseId, exerciseId, threshold, deadline, groupId, 0)
            }
            getElemsByClass("go-back").onVanillaClick(true) {
                buildTeacherStudentsList(
                    courseId,
                    courseExerciseId,
                    exerciseId,
                    threshold,
                    deadline,
                    groupId,
                    offset - PAGE_STEP
                )
            }
        }

        if (paginationConf?.canGoForward.isNotNullAndTrue) {
            getElemsByClass("go-forward").onVanillaClick(true) {
                buildTeacherStudentsList(
                    courseId,
                    courseExerciseId,
                    exerciseId,
                    threshold,
                    deadline,
                    groupId,
                    offset + PAGE_STEP
                )
            }
            getElemsByClass("go-last").onVanillaClick(true) {
                buildTeacherStudentsList(
                    courseId,
                    courseExerciseId,
                    exerciseId,
                    threshold,
                    deadline,
                    groupId,
                    getLastPageOffset(studentTotal, PAGE_STEP)
                )
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
                buildStudentTab(
                    courseId,
                    courseExerciseId,
                    exerciseId,
                    threshold,
                    deadline,
                    id,
                    givenName,
                    familyName,
                    false
                )
            }
        }

        initTooltips()
    }


    private fun buildStudentTab(
        courseId: String, courseExerciseId: String, exerciseId: String, threshold: Int, deadline: Date?,
        studentId: String, givenName: String, familyName: String, isAllSubsOpen: Boolean
    ) {

        suspend fun addAssessment(grade: Int, feedback: String, submissionId: String) {
            val assMap: MutableMap<String, Any> = mutableMapOf("grade" to grade)
            if (feedback.isNotBlank())
                assMap["feedback"] = feedback

            debug { "Posting assessment $assMap" }

            fetchEms("/teacher/courses/$courseId/exercises/$courseExerciseId/submissions/$submissionId/assessments",
                ReqMethod.POST, assMap, successChecker = { http200 }).await()
        }

        fun toggleAddGradeBox(submissionId: String, validGrade: Int?) {
            if (getElemByIdOrNull("add-grade-wrap") == null) {
                // Grading box is not visible
                debug { "Open add grade" }
                getElemById("add-grade-section").innerHTML = tmRender(
                    "tm-teach-exercise-add-grade", mapOf(
                        "gradePrefill" to validGrade,
                        "hasGradePrefill" to (validGrade != null),
                        "feedbackLabel" to Str.addAssessmentFeedbackLabel,
                        "gradeLabel" to Str.addAssessmentGradeLabel,
                        "gradeValidationError" to Str.addAssessmentGradeValidErr,
                        "addGradeButton" to Str.addAssessmentButtonLabel,
                        "feedbackEmailNote" to Str.feedbackEmailNote,
                    )
                )

                getElemById("grade-button").onVanillaClick(true) {
                    val grade = getElemByIdAs<HTMLInputElement>("grade").valueAsNumber.toInt()
                    val feedback = getElemByIdAs<HTMLTextAreaElement>("feedback").value
                    MainScope().launch {
                        addAssessment(grade, feedback, submissionId)
                        successMessage { Str.assessmentAddedMsg }
                        buildStudentTab(
                            courseId,
                            courseExerciseId,
                            exerciseId,
                            threshold,
                            deadline,
                            studentId,
                            givenName,
                            familyName,
                            isSubmissionBoxVisible()
                        )
                        buildTeacherStudents(courseId, courseExerciseId, exerciseId, threshold, deadline)
                    }
                }

                getElemById("add-grade-link").textContent = Str.closeToggleLink

            } else {
                // Grading box is visible
                debug { "Close add grade" }
                getElemById("add-grade-section").clear()
                getElemById("add-grade-link").textContent = Str.addAssessmentLink
            }
        }

        fun paintSubmission(
            id: String, number: Int, time: Date, solution: String, isLast: Boolean,
            gradeAuto: Int?, feedbackAuto: String?, gradeTeacher: Int?, feedbackTeacher: String?
        ) {

            getElemById("submission-part").innerHTML = tmRender(
                "tm-teach-exercise-student-submission-sub", mapOf(
                    "id" to id,
                    "submissionLabel" to Str.submissionHeading,
                    "submissionNo" to number,
                    "latestSubmissionLabel" to Str.latestSubmissionSuffix,
                    "notLatestSubmissionLabel" to Str.oldSubmissionNote,
                    "notLatestSubmissionLink" to Str.toLatestSubmissionLink,
                    "isLatest" to isLast,
                    "timeLabel" to Str.submissionTimeLabel,
                    "time" to EzDate(time).toHumanString(EzDate.Format.FULL),
                    "addGradeLink" to Str.addAssessmentLink,
                    "solution" to solution
                )
            )

            getElemById("assessment-auto").innerHTML = """<ez-dst id='$feedbackDstSub'></ez-dst>"""

            val validGrade = when {
                gradeTeacher != null ->
                    CourseExercisesStudentDAO.ValidGrade(gradeTeacher, ExerciseDAO.GraderType.TEACHER)

                gradeAuto != null ->
                    CourseExercisesStudentDAO.ValidGrade(gradeAuto, ExerciseDAO.GraderType.AUTO)

                else -> null

            }

            if (validGrade != null)
                renderAssessmentSub(validGrade, feedbackAuto, false, feedbackTeacher)

            CodeMirror.fromTextArea(
                getElemById("student-submission"), objOf(
                    "mode" to "python",
                    "theme" to "idea",
                    "lineNumbers" to true,
                    "autoRefresh" to true,
                    "viewportMargin" to 100,
                    "readOnly" to true
                )
            )

            getElemByIdOrNull("add-grade-link")?.onVanillaClick(true) { toggleAddGradeBox(id, validGrade?.grade) }

            getElemByIdOrNull("last-submission-link")?.onVanillaClick(true) {
                val isAllSubsBoxOpen = getElemByIdOrNull("all-submissions-wrap") != null
                buildStudentTab(
                    courseId,
                    courseExerciseId,
                    exerciseId,
                    threshold,
                    deadline,
                    studentId,
                    givenName,
                    familyName,
                    isAllSubsBoxOpen
                )
            }
        }

        suspend fun paintSubmissionBox() {
            getElemById("all-submissions-section").innerHTML = tmRender(
                "tm-teach-exercise-all-submissions-placeholder", mapOf(
                    "text" to Str.loadingAllSubmissions
                )
            )

            val submissionsWrap =
                fetchEms("/teacher/courses/$courseId/exercises/$courseExerciseId/submissions/all/students/$studentId",
                    ReqMethod.GET, successChecker = { http200 }).await()
                    .parseTo(TeacherSubmissions.serializer()).await()

            data class SubData(
                val number: Int, val isLast: Boolean, val time: Date, val solution: String,
                val gradeAuto: Int?, val feedbackAuto: String?, val gradeTeacher: Int?, val feedbackTeacher: String?
            )

            val submissionIdMap = mutableMapOf<String, SubData>()
            var submissionNumber = submissionsWrap.count
            val submissions = submissionsWrap.submissions.map {

                submissionIdMap[it.id] = SubData(
                    submissionNumber, submissionNumber == submissionsWrap.count,
                    it.created_at, it.solution, it.grade_auto, it.feedback_auto, it.grade_teacher, it.feedback_teacher
                )

                val submissionMap = mutableMapOf<String, Any?>(
                    "autoLabel" to Str.gradedAutomatically,
                    "teacherLabel" to Str.gradedByTeacher,
                    "missingLabel" to Str.notGradedYet,
                    "id" to it.id,
                    "number" to submissionNumber--,
                    "time" to EzDate(it.created_at).toHumanString(EzDate.Format.FULL)
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

            val selectedSubId = getElemBySelectorOrNull("[data-active-sub]")?.getAttribute("data-active-sub")
            debug { "Selected submission: $selectedSubId" }

            getElemById("all-submissions-section").innerHTML = tmRender(
                "tm-teach-exercise-all-submissions", mapOf(
                    "submissions" to submissions
                )
            )

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
                        paintSubmission(
                            id, sub.number, sub.time, sub.solution, sub.isLast,
                            sub.gradeAuto, sub.feedbackAuto, sub.gradeTeacher, sub.feedbackTeacher
                        )
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
                getElemById("all-submissions-link").textContent = Str.closeToggleLink
                paintSubmissionBox()
            } else {
                debug { "Close all submissions" }
                getElemById("all-submissions-section").clear()
                getElemById("all-submissions-link").textContent = Str.allSubmissionsLink
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

        // Back in student tab should return to submissions tab
        // will deregister on first
        backAbort.abort()
        backAbort = AbortController()
        window.addEventListener("popstate", { event ->
            event as PopStateEvent

            // Only try to do something if we're still on the correct page
            val t = getElemByIdOrNull("tabs")
            if (t != null) {
                if (tabs.index == 3) {
                    debug { "Back, returning to submissions tab" }
                    Materialize.Tabs.getInstance(t)?.select("students")
                } else {
                    window.history.back()
                }
            }

            backAbort.abort()

        }, objOf("signal" to backAbort.signal))

        if (window.location.hash != "#sub")
            window.history.pushState(null, "", "#sub")

        MainScope().launch {
            val submissions =
                fetchEms("/teacher/courses/$courseId/exercises/$courseExerciseId/submissions/all/students/$studentId?limit=1",
                    ReqMethod.GET, successChecker = { http200 }).await()
                    .parseTo(TeacherSubmissions.serializer()).await()

            val submission = submissions.submissions[0]

            getElemById("student").innerHTML = tmRender("tm-teach-exercise-student-submission", emptyMap())
            getElemById("all-submissions-part").innerHTML = tmRender(
                "tm-teach-exercise-student-submission-all", mapOf(
                    "allSubmissionsLink" to Str.allSubmissionsLink
                )
            )
            paintSubmission(
                submission.id, submissions.count, submission.created_at, submission.solution, true,
                submission.grade_auto, submission.feedback_auto, submission.grade_teacher, submission.feedback_teacher
            )

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
        getElemBySelectorOrNull("[data-sub-id='$selectedSubmissionid']")?.apply {
            removeAttribute("href")
            addClass("inactive")
        }
    }


    private fun renderAutoLoaderTesting() = renderAutoLoader(feedbackDstTesting)

    private fun renderAutoLoader(dst: String) {
        val loader = AutogradeLoaderComp(true, null, dst)
        loader.rebuild()
    }

    private fun renderAutoAssessmentTesting(
        grade: Int,
        autoFeedback: String?,
        failed: Boolean
    ) =
        renderAutoAssessment(
            CourseExercisesStudentDAO.ValidGrade(grade, ExerciseDAO.GraderType.AUTO),
            autoFeedback,
            null,
            failed,
            feedbackDstTesting
        )

    private fun renderAssessmentSub(
        validGrade: CourseExercisesStudentDAO.ValidGrade,
        autoFeedback: String?,
        failed: Boolean,
        teacherFeedback: String?
    ) =
        renderAutoAssessment(validGrade, autoFeedback, teacherFeedback, failed, feedbackDstSub)

    private fun renderAutoAssessment(
        validGrade: CourseExercisesStudentDAO.ValidGrade?,
        autoFeedback: String?,
        teacherFeedback: String?,
        failed: Boolean,
        feedbackDst: String
    ) {
        val view = ExerciseFeedbackComp(validGrade, autoFeedback, teacherFeedback, failed, null, feedbackDst)
        view.rebuild()
    }


    private fun initTooltips() {
        Materialize.Tooltip.init(getNodelistBySelector(".tooltipped"))
    }

    fun link(courseId: String, courseExerciseId: String) =
        constructPathLink(mapOf("courseId" to courseId, "courseExerciseId" to courseExerciseId))
}
