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
                    TabsComp.Tab("Kontroll", Icons.knobs) {
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
                        TabsComp.Tab("Katseta", Icons.robot) {
                            TestingTabComp(
                                courseEx.exercise_id,
                                courseEx.solution_file_name,
                                courseEx.solution_file_type,
                                it
                            )
                        }
                    )
                add(
                    TabsComp.Tab("Esitused", Icons.courseParticipants) {
                        TeacherCourseExerciseSubmissionsListTabComp(
                            courseId, courseExId, courseEx.threshold,
                            { openStudent(it) },
                            it
                        ).also { studentsTabComp = it }
                    })

                add(TabsComp.Tab("", Icons.user, visible = false) {
                    TeacherCourseExerciseStudentTabComp(
                        "", "", "",
                        "", "",
                        { openNextStudent(it) }, { openPrevStudent(it) }, it
                    )
                        .also { submissionTabComp = it }
                }.also { submissionTabId = it.id })
            },
            parent = this
        )

        // TODO: sidenav
    }

    override fun render() = template(
        """
            <ez-course-exercise>
                <ez-block-container>
                    <ez-block id='${exerciseTextComp.dstId}' style='width: 45rem; max-width: 80rem; overflow: auto;'></ez-block>
                    <ez-block id='${tabs.dstId}' style='width: 45rem; max-width: 80rem; padding-top: 1rem; padding-bottom: 2rem; overflow: auto;'></ez-block>
                </ez-block-container>
            </ez-course-exercise>
        """.trimIndent(),

        )

    private suspend fun openStudent(student: TeacherCourseExerciseSubmissionsListTabComp.StudentProps) {
        tabs.setTabTitle(submissionTabId, student.givenName + " " + student.familyName[0])
        tabs.setTabVisible(submissionTabId, true)
        tabs.activateTab(submissionTabId)

        submissionTabComp.setStudent(student.id, "${student.givenName} ${student.familyName}")
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

}