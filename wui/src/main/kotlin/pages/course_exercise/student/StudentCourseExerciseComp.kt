package pages.course_exercise.student

import CONTENT_CONTAINER_ID
import cache.BasicCourseInfo
import components.TabsComp
import components.TwoColDividerComp
import dao.CourseExercisesStudentDAO
import kotlinx.coroutines.await
import pages.Title
import pages.course_exercise.CourseExerciseTextComp
import pages.course_exercise.ExerciseSummaryPage
import pages.exercise_library.createPathChainSuffix
import pages.sidenav.Sidenav
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.doInPromise
import storage.Key
import template
import translation.Str


class StudentCourseExerciseComp(
    private val courseId: String,
    private val courseExId: String,
    private val setPathSuffix: (String) -> Unit
) : Component(null, CONTENT_CONTAINER_ID) {

    private lateinit var exerciseTextComp: CourseExerciseTextComp
    private lateinit var tabs: TabsComp

    private lateinit var colDividerComp: TwoColDividerComp

    private lateinit var submissionsTab: CourseExerciseStudentSubmissionsTabComp
    private lateinit var oldSubmissionTab: CourseExerciseStudentOldSubmissionTabComp
    private val oldSubmissionTabId = IdGenerator.nextId()


    override val children: List<Component>
        get() = listOf(exerciseTextComp, tabs, colDividerComp)

    override fun create() = doInPromise {
        val courseEx = CourseExercisesStudentDAO.getCourseExerciseDetails(courseId, courseExId).await()
        val courseTitle = BasicCourseInfo.get(courseId).await().effectiveTitle

        Title.update {
            it.pageTitle = courseEx.effective_title
            it.parentPageTitle = courseTitle
        }

        setPathSuffix(createPathChainSuffix(listOf(courseEx.effective_title)))

        exerciseTextComp = CourseExerciseTextComp(courseEx.effective_title, courseEx.text_html, courseEx.deadline, this)
        submissionsTab = CourseExerciseStudentSubmissionsTabComp(courseId, courseExId, { openSubmission(it) }, this)
        oldSubmissionTab = CourseExerciseStudentOldSubmissionTabComp(
            courseId, courseExId, null,
            courseEx.solution_file_name,
            courseEx.solution_file_type,
            this
        )

        tabs = TabsComp(
            TabsComp.Type.PRIMARY,
            listOf(
                TabsComp.Tab(
                    Str.tabSubmit,
                    comp = CourseExerciseStudentSubmitTabComp(
                        courseId,
                        courseExId,
                        courseEx.grader_type,
                        courseEx.is_open,
                        courseEx.solution_file_name,
                        courseEx.solution_file_type,
                        ::updateSubmissions,
                        this
                    )
                ),
                TabsComp.Tab(
                    Str.tabMySubmissions,
                    comp = submissionsTab
                ),
                TabsComp.Tab(
                    "",
                    id = oldSubmissionTabId,
                    visible = false,
                    comp = oldSubmissionTab,
                )
            ),
        )

        colDividerComp = TwoColDividerComp(
            Key.STUDENT_COURSE_EXERCISE_PANEL_EXPAND_STATE,
            parent = this
        )
    }

    override fun render() = template(
        """
            <ez-course-exercise>
                <ez-block-container>
                    <ez-block id='${exerciseTextComp.dstId}' style='width: 45rem; max-width: 120rem; overflow: auto;'></ez-block>
                    <ez-block id='${colDividerComp.dstId}'></ez-block>
                    <ez-block id='${tabs.dstId}' style='width: 45rem; padding-top: 1rem; padding-bottom: 2rem; overflow: auto;'></ez-block>
                </ez-block-container>
            </ez-course-exercise>
        """.trimIndent(),
    )

    private fun updateSubmissions() {
        Sidenav.refresh(ExerciseSummaryPage.sidenavSpec, true)
        submissionsTab.createAndBuild()
    }

    private suspend fun openSubmission(submission: CourseExercisesStudentDAO.StudentSubmission) {
        tabs.setTabTitle(oldSubmissionTabId, "# " + submission.number.toString())
        tabs.setTabVisible(oldSubmissionTabId, true)
        tabs.activateTab(oldSubmissionTabId)
        oldSubmissionTab.setSubmission(submission)
    }
}