package pages.course_exercise

import CONTENT_CONTAINER_ID
import cache.BasicCourseInfo
import components.PageTabsComp
import dao.CourseExercisesStudentDAO
import kotlinx.coroutines.await
import pages.Title
import pages.exercise_library.createPathChainSuffix
import pages.sidenav.Sidenav
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import template


class StudentCourseExerciseComp(
    private val courseId: String,
    private val courseExId: String,
    private val setPathSuffix: (String) -> Unit
) : Component(null, CONTENT_CONTAINER_ID) {


    private lateinit var exerciseTextComp: CourseExerciseTextComp
    private lateinit var tabs: PageTabsComp

    private lateinit var submissionsTab: CourseExerciseStudentSubmissionsTabComp

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
                PageTabsComp.Tab("Esita", preselected = true,
                    compProvider = {
                        CourseExerciseStudentSubmitTabComp(
                            courseId,
                            courseExId,
                            courseEx.grader_type,
                            ::updateSubmissions,
                            it
                        )
                    }),
                PageTabsComp.Tab("Kõik esitused",
                    compProvider = {
                        CourseExerciseStudentSubmissionsTabComp(courseId, courseExId, courseEx.threshold, it)
                            .also { submissionsTab = it }
                    }),
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
}