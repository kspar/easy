package pages.course_exercise.teacher

import EzDate
import Icons
import components.EzCollComp
import dao.CourseExercisesStudentDAO
import dao.CourseExercisesTeacherDAO
import dao.ExerciseDAO
import kotlinx.coroutines.await
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import translation.Str

class TeacherCourseExerciseSubmissionsListTabComp(
    private val courseId: String,
    private val courseExId: String,
    private val threshold: Int,
    private val onOpenStudent: suspend (StudentProps) -> Unit,
    parent: Component
) : Component(parent) {

    data class StudentSubmission(
        val id: String, val time: EzDate, val grade: Int?, val gradedBy: ExerciseDAO.GraderType?,
    )

    data class StudentProps(
        val id: String, val givenName: String, val familyName: String, val groups: String?,
        val submission: StudentSubmission?, val status: CourseExercisesStudentDAO.SubmissionStatus,
    )

    private lateinit var studentProps: List<StudentProps>

    private lateinit var coll: EzCollComp<StudentProps>

    override val children: List<Component>
        get() = listOf(coll)

    override fun create() = doInPromise {
        studentProps =
            CourseExercisesTeacherDAO.getLatestSubmissions(courseId, courseExId).await().students.map {
                StudentProps(
                    it.student_id, it.given_name, it.family_name, it.groups,
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
                        it.grade >= threshold -> CourseExercisesStudentDAO.SubmissionStatus.COMPLETED
                        else -> CourseExercisesStudentDAO.SubmissionStatus.STARTED
                    }
                )
            }

        val submissions =
            studentProps.map {
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
//                    titleStatus = if (it.submission != null) EzCollComp.TitleStatus.NORMAL else EzCollComp.TitleStatus.INACTIVE,
//                    titleInteraction = if (it.submission != null) EzCollComp.TitleAction<StudentProps> {
//                        onOpenStudent(it)
//                    } else null,
                    titleInteraction = EzCollComp.TitleAction<StudentProps> { onOpenStudent(it) },

//                                    topAttr = if (it.submission?.grade != null && it.submission.gradedBy != null)
//                                        EzCollComp.SimpleAttr(
//                                            "Punktid", "${it.submission.grade} / 100",
//                                            if (it.submission.gradedBy == ExerciseDAO.GraderType.AUTO) Icons.robot else Icons.teacherFace
//                                        ) else null,
//
                            // TODO: paint and icon if time > deadline
                            topAttr =
                    if (it.submission != null) {
                        EzCollComp.SimpleAttr(
                            "Esitamise aeg",
                            it.submission.time.toHumanString(EzDate.Format.DATE),
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
                    progressBar = EzCollComp.ProgressBar(CourseExercisesStudentDAO.translateStatusToProgress(it.status)),
                )
            }

        coll = EzCollComp(
            submissions,
            EzCollComp.Strings(Str.studentsSingular, Str.studentsPlural),
            parent = this
        )
    }

    fun getNextStudent(currentId: String): StudentProps? {
        val students = coll.getOrderedVisibleItems().map { it.props }
        val nextIdx = students.indexOfFirst { it.id == currentId } + 1
        return if (nextIdx == 0 || nextIdx >= students.size)
            null
        else
            students[nextIdx]
    }

    fun getPrevStudent(currentId: String): StudentProps? {
        val students = coll.getOrderedVisibleItems().map { it.props }
        val nextIdx = students.indexOfFirst { it.id == currentId } - 1
        return if (nextIdx < 0 || nextIdx >= students.size)
            null
        else
            students[nextIdx]
    }

}