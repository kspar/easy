package pages.course_exercise.teacher

import CONTENT_CONTAINER_ID
import Icons
import cache.BasicCourseInfo
import components.TabID
import components.TabsComp
import components.TwoColDividerComp
import dao.CourseExercisesTeacherDAO
import kotlinx.coroutines.await
import pages.Title
import pages.course_exercise.CourseExerciseTextComp
import pages.course_exercises_list.UpdateCourseExerciseModalComp
import pages.exercise_in_library.AutoAssessmentTabComp
import pages.exercise_in_library.ExercisePage
import pages.exercise_in_library.TestingTabComp
import pages.exercise_library.createPathChainSuffix
import pages.sidenav.Sidenav
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import storage.Key
import template
import translation.Str

class TeacherCourseExerciseComp(
    private val courseId: String,
    private val courseExId: String,
    private val preselectedStudentId: String?,
    // Hidden feature, mostly for testing
    private val tabId: String?,
    private val setPathSuffix: (String) -> Unit
) : Component(null, CONTENT_CONTAINER_ID) {


    private lateinit var exerciseTextComp: CourseExerciseTextComp
    private lateinit var tabs: TabsComp

    private lateinit var studentsTabComp: TeacherCourseExerciseSubmissionsListTabComp

    private lateinit var submissionTabComp: TeacherCourseExerciseStudentTabComp
    private lateinit var submissionTabId: TabID

    private lateinit var colDividerComp: TwoColDividerComp

    private lateinit var updateModal: UpdateCourseExerciseModalComp


    override val children: List<Component>
        get() = listOf(exerciseTextComp, tabs, colDividerComp, updateModal)

    override fun create() = doInPromise {
        val courseEx = CourseExercisesTeacherDAO.getCourseExerciseDetails(courseId, courseExId).await()
        val courseTitle = BasicCourseInfo.get(courseId).await().effectiveTitle

        Title.update {
            it.pageTitle = courseEx.effectiveTitle
            it.parentPageTitle = courseTitle
        }

        Sidenav.replacePageSection(
            Sidenav.PageSection(
                courseEx.effectiveTitle,
                buildList {
                    add(Sidenav.Action(Icons.settings, Str.exerciseSettings) {
                        val changed = updateModal.openWithClosePromise().await()
                        if (changed) {
                            createAndBuild().await()
                        }
                    })
                    if (courseEx.has_lib_access)
                        add(Sidenav.Link(Icons.library, Str.openInLib, ExercisePage.link(courseEx.exercise_id)))
                }
            )
        )

        setPathSuffix(createPathChainSuffix(listOf(courseEx.effectiveTitle)))

        exerciseTextComp = CourseExerciseTextComp(courseEx.effectiveTitle, courseEx.text_html, null, this)

        tabs = TabsComp(
            TabsComp.Type.PRIMARY,
            tabs = buildList {
                add(
                    TabsComp.Tab(
                        "Kontroll", Icons.knobs,
                        active = (tabId == "1")
                    ) {
                        val aaProps = if (courseEx.grading_script != null) {
                            AutoAssessmentTabComp.AutoAssessProps(
                                courseEx.grading_script,
                                courseEx.assets!!.associate { it.file_name to it.file_content },
                                courseEx.container_image!!,
                                courseEx.max_time_sec!!,
                                courseEx.max_mem_mb!!
                            )
                        } else null

                        AutoAssessmentTabComp(aaProps, courseEx.solution_file_name, courseEx.solution_file_type, {}, it)
                    }
                )

                if (courseEx.grading_script != null)
                    add(
                        TabsComp.Tab(
                            "Katseta", Icons.robot,
                            active = (tabId == "2")
                        ) {
                            TestingTabComp(
                                courseEx.exercise_id,
                                courseId,
                                courseEx.solution_file_name,
                                courseEx.solution_file_type,
                                it
                            )
                        }
                    )
                add(
                    TabsComp.Tab(
                        "Esitused", Icons.courseParticipants,
                        active = true
                    ) {
                        TeacherCourseExerciseSubmissionsListTabComp(
                            courseId, courseExId,
                            { openStudent(it) },
                            ::updatePrevNextBtns,
                            it
                        ).also { studentsTabComp = it }
                    })

                add(TabsComp.Tab("", Icons.user, visible = false) {
                    TeacherCourseExerciseStudentTabComp(
                        courseId, courseExId, courseEx.exercise_id, courseEx.soft_deadline, courseEx.solution_file_name,
                        "", "", "", null, null,
                        { openNextStudent(it) }, { openPrevStudent(it) }, { updatePrevNextBtns() },
                        it
                    ).also { submissionTabComp = it }
                }.also { submissionTabId = it.id })
            },
            parent = this
        )

        colDividerComp = TwoColDividerComp(
            Key.TEACHER_COURSE_EXERCISE_PANEL_EXPAND_STATE,
            parent = this
        )

        updateModal = UpdateCourseExerciseModalComp(
            courseId,
            UpdateCourseExerciseModalComp.CourseExercise(
                courseExId, courseEx.title, courseEx.title_alias, courseEx.threshold, courseEx.student_visible,
                courseEx.student_visible_from, courseEx.soft_deadline, courseEx.hard_deadline
            ),
            parent = this
        )
    }

    override fun render() = template(
        """
            <ez-course-exercise>
                <ez-block-container>
                    <!-- max width only for text element because reading a very wide column is not nice -->
                    <ez-block id='${exerciseTextComp.dstId}' style='width: 45rem; max-width: 120rem; overflow: auto;'></ez-block>
                    <ez-block id='${colDividerComp.dstId}'></ez-block>
                    <!-- overflow on the following block would cause menus to clip on the edge of the block -->
                    <ez-block id='${tabs.dstId}' style='width: 45rem; padding-top: 1rem; padding-bottom: 2rem;'></ez-block>
                </ez-block-container>
            </ez-course-exercise>
            $updateModal
        """.trimIndent(),
    )

    override fun postChildrenBuilt() {
        doInPromise {
            if (preselectedStudentId != null) {
                val selectedSubmission = CourseExercisesTeacherDAO.getLatestSubmissions(courseId, courseExId).await()
                    .latest_submissions.firstOrNull { it.student_id == preselectedStudentId }
                if (selectedSubmission != null) {
                    openStudent(selectedSubmission)
                }
            }
        }
    }

    private suspend fun openStudent(student: CourseExercisesTeacherDAO.LatestStudentSubmission) {
        tabs.setTabTitle(submissionTabId, student.given_name + " " + student.family_name[0])
        tabs.setTabVisible(submissionTabId, true)
        tabs.activateTab(submissionTabId)

        submissionTabComp.setStudent(
            student.student_id, student.given_name, student.family_name, student.submission?.id, student.submission?.id
        )
        updatePrevNextBtns()

        if (student.submission != null)
            CourseExercisesTeacherDAO.setSubmissionSeenStatus(
                courseId, courseExId, true, listOf(student.submission.id)
            ).await()

        // Refresh data in the list
        studentsTabComp.createAndBuild().await()
    }

    private suspend fun openPrevStudent(currentId: String) {
        val prevStudent = studentsTabComp.getPrevStudent(currentId)
        if (prevStudent != null)
            openStudent(prevStudent)
    }

    private suspend fun openNextStudent(currentId: String) {
        val nextStudent = studentsTabComp.getNextStudent(currentId)
        if (nextStudent != null)
            openStudent(nextStudent)
    }

    private fun updatePrevNextBtns() {
        val currentStudentId = submissionTabComp.studentId
        submissionTabComp.setPrevNextBtns(
            studentsTabComp.getPrevStudent(currentStudentId)?.name,
            studentsTabComp.getNextStudent(currentStudentId)?.name,
        )
    }
}