package pages.course_exercise.teacher

import CONTENT_CONTAINER_ID
import Icons
import cache.BasicCourseInfo
import components.TabID
import components.TabsComp
import dao.CourseExercisesTeacherDAO
import kotlinx.coroutines.await
import pages.Title
import pages.course_exercise.CourseExerciseTextComp
import pages.exercise_in_library.AutoAssessmentTabComp
import pages.exercise_in_library.TestingTabComp
import pages.exercise_library.createPathChainSuffix
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import template

class TeacherCourseExerciseComp(
    private val courseId: String,
    private val courseExId: String,
    private val tabId: String?,
    private val setPathSuffix: (String) -> Unit
) : Component(null, CONTENT_CONTAINER_ID) {


    private lateinit var exerciseTextComp: CourseExerciseTextComp
    private lateinit var tabs: TabsComp

    private lateinit var studentsTabComp: TeacherCourseExerciseSubmissionsListTabComp

    private lateinit var submissionTabComp: TeacherCourseExerciseStudentTabComp
    private lateinit var submissionTabId: TabID

    override val children: List<Component>
        get() = listOf(exerciseTextComp, tabs)

    override fun create() = doInPromise {
        val courseEx = CourseExercisesTeacherDAO.getCourseExerciseDetails(courseId, courseExId).await()
        val courseTitle = BasicCourseInfo.get(courseId).await().effectiveTitle

        Title.update {
            it.pageTitle = courseEx.effectiveTitle
            it.parentPageTitle = courseTitle
        }

        setPathSuffix(createPathChainSuffix(listOf(courseEx.effectiveTitle)))

        exerciseTextComp = CourseExerciseTextComp(courseEx.effectiveTitle, courseEx.text_html, null, this)




        tabs = TabsComp(
            TabsComp.Type.SECONDARY,
            tabs = buildList {
                add(
                    TabsComp.Tab(
                        "Kontroll", Icons.knobs,
                        // FIXME: for testing
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
                            // FIXME: for testing
                            active = (tabId == "2")
                        ) {
                            TestingTabComp(
                                courseEx.exercise_id,
                                courseEx.solution_file_name,
                                courseEx.solution_file_type,
                                it
                            )
                        }
                    )
                add(
                    TabsComp.Tab(
                        "Esitused", Icons.courseParticipants,
                        // FIXME: for testing
                        active = (tabId == "3")
                    ) {
                        TeacherCourseExerciseSubmissionsListTabComp(
                            courseId, courseExId,
                            { openStudent(it) },
                            ::updatePrevNextBtns,
                            it
                        ).also { studentsTabComp = it }
                    })

                // TODO: might want to make the query param 'student' and remove this prefix once testing tab ids are not needed anymore,
                //  also rm this query param when selecting other tabs and add it when selecting this tab or opening a new student
                val tabIdPrefix = "student:"
                val selectedStudentId = if (tabId != null && tabId.startsWith(tabIdPrefix))
                    tabId.substring(tabIdPrefix.length - 1)
                else null

                add(TabsComp.Tab(
                    "", Icons.user,
                    visible = selectedStudentId != null,
                    active = selectedStudentId != null,
                ) {
                    TeacherCourseExerciseStudentTabComp(
                        courseId, courseExId, courseEx.exercise_id, courseEx.soft_deadline,
                        selectedStudentId.orEmpty(),
//                        "20816",
                        null,
                        // TODO: update title
//                        { tabs.setTabTitle(submissionTabId, it.givenName + " " + it.familyName[0])},
                        { },
                        { openNextStudent(it) }, { openPrevStudent(it) },
                        it
                    )
                        .also { submissionTabComp = it }
                }.also { submissionTabId = it.id })
            },
            parent = this
        )

        // TODO: sidenav + download functionality from legacy button
    }

    override fun render() = template(
        """
            <ez-course-exercise>
                <ez-block-container>
                    <ez-block id='${exerciseTextComp.dstId}' style='width: 45rem; max-width: 80rem; overflow: auto;'></ez-block>
                    <!-- overflow on the following block would cause menus to clip on the edge of the block -->
                    <ez-block id='${tabs.dstId}' style='width: 45rem; max-width: 80rem; padding-top: 1rem; padding-bottom: 2rem;'></ez-block>
                </ez-block-container>
            </ez-course-exercise>
        """.trimIndent(),
    )

    private suspend fun openStudent(student: CourseExercisesTeacherDAO.LatestStudentSubmission) {
        tabs.setTabTitle(submissionTabId, student.given_name + " " + student.family_name[0])
        tabs.setTabVisible(submissionTabId, true)
        tabs.activateTab(submissionTabId)

        submissionTabComp.setStudent(student.student_id, student.submission?.id)
        updatePrevNextBtns()
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
            studentsTabComp.getPrevStudent(currentStudentId)?.let { "${it.given_name} ${it.family_name}" },
            studentsTabComp.getNextStudent(currentStudentId)?.let { "${it.given_name} ${it.family_name}" },
        )
    }

}