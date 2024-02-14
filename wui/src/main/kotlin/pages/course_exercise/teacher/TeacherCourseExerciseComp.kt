package pages.course_exercise.teacher

import CONTENT_CONTAINER_ID
import EzDate
import Icons
import cache.BasicCourseInfo
import components.EzCollComp
import components.TabID
import components.TabsComp
import components.text.StringComp
import dao.CourseExercisesStudentDAO
import dao.CourseExercisesStudentDAO.translateStatusToProgress
import dao.CourseExercisesTeacherDAO
import dao.ExerciseDAO
import kotlinx.coroutines.await
import pages.Title
import pages.course_exercise.CourseExerciseTextComp
import pages.exercise_in_library.AutoAssessmentTabComp
import pages.exercise_in_library.TestingTabComp
import pages.exercise_library.createPathChainSuffix
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import template
import translation.Str

class TeacherCourseExerciseComp(
    private val courseId: String,
    private val courseExId: String,
    private val setPathSuffix: (String) -> Unit
) : Component(null, CONTENT_CONTAINER_ID) {

    private lateinit var exerciseTextComp: CourseExerciseTextComp
    private lateinit var tabs: TabsComp

    private lateinit var studentTabId: TabID

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

        // TODO: should probably extract the submissions tab to a separate component
        data class StudentSubmission(
            val id: String, val time: EzDate, val grade: Int?, val gradedBy: ExerciseDAO.GraderType?,
        )

        data class StudentProps(
            val givenName: String, val familyName: String, val groups: String?,
            val submission: StudentSubmission?, val status: CourseExercisesStudentDAO.SubmissionStatus,
        )

        val props =
            CourseExercisesTeacherDAO.getLatestSubmissions(courseId, courseExId).await().students.map {
                StudentProps(
                    it.given_name, it.family_name, it.groups,
                    if (it.submission_id != null && it.submission_time != null)
                        StudentSubmission(
                            it.submission_id,
                            it.submission_time,
                            it.grade,
                            it.graded_by
                        ) else null,
                    when {
                        it.submission_id == null -> CourseExercisesStudentDAO.SubmissionStatus.UNSTARTED
                        it.grade == null -> CourseExercisesStudentDAO.SubmissionStatus.UNGRADED
                        it.grade >= courseEx.threshold -> CourseExercisesStudentDAO.SubmissionStatus.COMPLETED
                        else -> CourseExercisesStudentDAO.SubmissionStatus.STARTED
                    }
                )
            }

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
                        val submissions =
                            props.map {
                                val studentLetters = it.givenName[0].uppercase() + it.familyName[0].uppercase()
                                EzCollComp.Item(
                                    it,
//                                    EzCollComp.ItemTypeText("#" + Random.nextInt(1, 20).toString()),
                                    EzCollComp.ItemTypeIcon(
                                        when (it.submission?.gradedBy) {
                                            ExerciseDAO.GraderType.AUTO -> Icons.robot
                                            ExerciseDAO.GraderType.TEACHER -> Icons.teacherFace
                                            null -> Icons.dotsHorizontal
                                        }
                                    ),
//                                    if (Random.nextBoolean())
//                                        EzCollComp.ItemTypeIcon(Icons.robot)
//                                    else EzCollComp.ItemTypeIcon(Icons.teacherFace),
//                                    EzCollComp.ItemTypeIcon(Icons.user),
                                    it.givenName + " " + it.familyName,
                                    titleStatus = if (it.submission != null) EzCollComp.TitleStatus.NORMAL else EzCollComp.TitleStatus.INACTIVE,
                                    titleInteraction = if (it.submission != null) EzCollComp.TitleAction<StudentProps> {
                                        tabs.setTabTitle(studentTabId, it.givenName + " " + it.familyName[0])
                                        tabs.setTabVisible(studentTabId, true)
                                        tabs.activateTab(studentTabId)
                                    } else null,

//                                    topAttr = if (it.submission?.grade != null && it.submission.gradedBy != null)
//                                        EzCollComp.SimpleAttr(
//                                            "Punktid", "${it.submission.grade} / 100",
//                                            if (it.submission.gradedBy == ExerciseDAO.GraderType.AUTO) Icons.robot else Icons.teacherFace
//                                        ) else null,
//
                                    // TODO: paint and icon if time > deadline
                                    topAttr = if (it.submission != null) {
                                        EzCollComp.SimpleAttr(
                                            "Esitamise aeg",
                                            it.submission.time.toHumanString(EzDate.Format.DATE),
                                            Icons.pending
                                        )
                                    } else null,
//
//                                    topAttr = if (it.groups != null) EzCollComp.SimpleAttr(
//                                        "Rühmad",
//                                        it.groups,
//                                        Icons.groupsUnf
//                                    ) else null,
                                    bottomAttrs = buildList {
//                                        if (it.submission?.grade != null && it.submission.gradedBy != null)
//                                            add(
//                                                EzCollComp.SimpleAttr(
//                                                    "Punktid", "${it.submission.grade}/100",
//                                                    if (it.submission.gradedBy == ExerciseDAO.GraderType.AUTO) Icons.robot else Icons.teacherFace
//                                                )
//                                            )
//                                        if (it.submission != null) {
//                                            add(EzCollComp.SimpleAttr("Esitus", "# 4"))
//                                            add(
//                                                EzCollComp.SimpleAttr(
//                                                    "Esitamise aeg",
//                                                    it.submission.time.toHumanString(EzDate.Format.DATE),
//                                                    Icons.pending
//                                                )
//                                            )
//                                        }

                                    },
                                    // TODO: need status or can also calculate from threshold
                                    progressBar = EzCollComp.ProgressBar(translateStatusToProgress(it.status)),
                                )
                            }

                        EzCollComp(
                            submissions,
                            EzCollComp.Strings(Str.studentsSingular, Str.studentsPlural),
                            parent = it
                        )

                    })

                val t = TabsComp.Tab("Murelin S", Icons.user, visible = false) {
                    StringComp("Murel", it)
                }
                studentTabId = t.id
                add(t)
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

}