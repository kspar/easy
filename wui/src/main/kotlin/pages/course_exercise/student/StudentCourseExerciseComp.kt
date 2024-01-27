package pages.course_exercise.student

import CONTENT_CONTAINER_ID
import cache.BasicCourseInfo
import components.PageTabsComp
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
import template
import translation.Str


class StudentCourseExerciseComp(
    private val courseId: String,
    private val courseExId: String,
    private val setPathSuffix: (String) -> Unit
) : Component(null, CONTENT_CONTAINER_ID) {


    private lateinit var exerciseTextComp: CourseExerciseTextComp
    private lateinit var tabs: PageTabsComp

    private lateinit var submissionsTab: CourseExerciseStudentSubmissionsTabComp
    private lateinit var oldSubmissionTab: CourseExerciseStudentOldSubmissionTabComp
    private val oldSubmissionTabId = IdGenerator.nextId()


    override val children: List<Component>
        get() = listOf(exerciseTextComp, tabs)

    override fun create() = doInPromise {
        val courseEx = CourseExercisesStudentDAO.getCourseExerciseDetails(courseId, courseExId).await()
        val courseTitle = BasicCourseInfo.get(courseId).await().effectiveTitle

        Title.update {
            it.pageTitle = courseEx.effective_title
            it.parentPageTitle = courseTitle
        }

        setPathSuffix(createPathChainSuffix(listOf(courseEx.effective_title)))

        exerciseTextComp = CourseExerciseTextComp(courseEx, this)
        tabs = PageTabsComp(
            type = PageTabsComp.Type.SUBPAGE,
            tabs = listOf(
                PageTabsComp.Tab(Str.tabSubmit, preselected = true,
                    compProvider = {
                        CourseExerciseStudentSubmitTabComp(
                            courseId,
                            courseExId,
                            courseEx.grader_type,
                            courseEx.is_open,
                            courseEx.solution_file_name,
                            courseEx.solution_file_type,
                            ::updateSubmissions,
                            it
                        )
                    }),
                PageTabsComp.Tab(Str.tabMySubmissions,
                    compProvider = {
                        CourseExerciseStudentSubmissionsTabComp(
                            courseId,
                            courseExId,
                            courseEx.threshold,
                            { openSubmission(it) },
                            it
                        ).also { submissionsTab = it }
                    }),
                PageTabsComp.Tab(
                    "",
                    id = oldSubmissionTabId, hidden = true,
                    compProvider = {
                        CourseExerciseStudentOldSubmissionTabComp(
                            null,
                            courseEx.solution_file_name,
                            courseEx.solution_file_type,
                            it
                        )
                            .also { oldSubmissionTab = it }
                    },
                ),
            ),
            parent = this
        )
    }

    override fun render() = template(
        """
            <ez-student-course-exercise>
                <ez-block-container>
                    <ez-block id='${exerciseTextComp.dstId}' style='width: 45rem; max-width: 80rem; overflow: auto;'></ez-block>
                    <ez-block id='${tabs.dstId}' style='width: 45rem; max-width: 80rem; padding-top: 1rem; padding-bottom: 5rem; overflow: auto;'></ez-block>
                </ez-block-container>
            </ez-student-course-exercise>
        """.trimIndent(),

        )

    private fun updateSubmissions() {
        Sidenav.refresh(ExerciseSummaryPage.sidenavSpec, true)
        submissionsTab.createAndBuild()
    }

    private suspend fun openSubmission(submission: CourseExercisesStudentDAO.StudentSubmission) {
        tabs.setTabTitle(oldSubmissionTabId, "#" + submission.number.toString())
        tabs.setSelectedTabById(oldSubmissionTabId)
        oldSubmissionTab.setSubmission(submission)
    }
}