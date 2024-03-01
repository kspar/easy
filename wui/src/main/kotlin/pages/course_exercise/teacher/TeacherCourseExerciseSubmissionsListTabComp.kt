package pages.course_exercise.teacher

import EzDate
import Icons
import components.EzCollComp
import dao.CourseExercisesStudentDAO
import dao.CourseExercisesTeacherDAO
import dao.ParticipantsDAO
import kotlinx.coroutines.await
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import translation.Str

class TeacherCourseExerciseSubmissionsListTabComp(
    private val courseId: String,
    private val courseExId: String,
    private val threshold: Int,
    private val onOpenStudent: suspend (StudentProps) -> Unit,
    private val onStudentListChange: () -> Unit,
    parent: Component
) : Component(parent) {

    data class StudentProps(
        val id: String,
        val givenName: String,
        val familyName: String,
        val groups: List<ParticipantsDAO.CourseGroup>,
        val submission: CourseExercisesTeacherDAO.StudentSubmission?,
        val status: CourseExercisesStudentDAO.SubmissionStatus,
    )


    private lateinit var studentProps: List<StudentProps>

    private lateinit var coll: EzCollComp<StudentProps>

    override val children: List<Component>
        get() = listOf(coll)

    override fun create() = doInPromise {
        studentProps = CourseExercisesTeacherDAO.getLatestSubmissions(courseId, courseExId).await()
            .latest_submissions.map {
                StudentProps(
                    it.student_id, it.given_name, it.family_name, it.groups,
                    it.latest_submission,
                    when {
                        it.latest_submission == null -> CourseExercisesStudentDAO.SubmissionStatus.UNSTARTED
                        it.latest_submission.grade == null -> CourseExercisesStudentDAO.SubmissionStatus.UNGRADED
                        it.latest_submission.grade.grade >= threshold -> CourseExercisesStudentDAO.SubmissionStatus.COMPLETED
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
                        when {
                            it.submission?.grade == null -> Icons.dotsHorizontal
                            it.submission.grade.is_autograde -> Icons.robot
                            else -> Icons.teacherFace
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
            strings = EzCollComp.Strings(Str.studentsSingular, Str.studentsPlural),
            filterGroups = listOf(
                EzCollComp.FilterGroup(
                    "Hinnatud", listOf(
                        // TODO: hindamata?
                        EzCollComp.Filter("Automaatselt hinnatud") { it.props.submission?.grade?.is_autograde == true },
                        EzCollComp.Filter("Õpetaja hinnatud") { it.props.submission?.grade?.is_autograde == false },
                    )
                ),
                // TODO: maybe not useful
                EzCollComp.FilterGroup(
                    "Esitus", listOf(
                        EzCollComp.Filter("Lahendus esitatud") { it.props.submission != null },
                        EzCollComp.Filter("Esitamata") { it.props.submission == null },
                    )
                ),
            ),
            sorters = buildList {
//                if (hasGroups)
//                    add(
//                        EzCollComp.Sorter("Rühma ja nime järgi",
//                            compareBy<EzCollComp.Item<StudentProps>, String?>(HumanStringComparator) { it.props.groups.getOrNull(0)?.name }
//                                .thenBy(HumanStringComparator) { it.props.groups.getOrNull(1)?.name }
//                                .thenBy(HumanStringComparator) { it.props.groups.getOrNull(2)?.name }
//                                .thenBy(HumanStringComparator) { it.props.groups.getOrNull(3)?.name }
//                                .thenBy(HumanStringComparator) { it.props.groups.getOrNull(4)?.name }
//                                .thenBy { it.props.lastName?.lowercase() ?: it.props.email.lowercase() }
//                                .thenBy { it.props.firstName?.lowercase() })
//                    )
                add(EzCollComp.Sorter("Nime järgi",
                    compareBy<EzCollComp.Item<StudentProps>> {
                        it.props.familyName.lowercase()
                    }.thenBy { it.props.givenName.lowercase() }
                ))
                add(EzCollComp.Sorter("Punktide järgi",
                    compareBy<EzCollComp.Item<StudentProps>> {
                        // nulls last
                        if (it.props.submission?.grade == null) 1 else 0
                    }.thenByDescending<EzCollComp.Item<StudentProps>> {
                        it.props.submission?.grade?.grade
                    }
                ))
                add(EzCollComp.Sorter("Esitamisaja järgi",
                    compareBy<EzCollComp.Item<StudentProps>> {
                        // nulls last
                        if (it.props.submission?.time == null) 1 else 0
                    }.thenBy<EzCollComp.Item<StudentProps>> {
                        it.props.submission?.time
                    }
                ))
            },
            compact = true,
            onConfChange = { onStudentListChange() },
            parent = this
        )
    }

    fun getNextStudent(currentId: String): StudentProps? {
        val students = coll.getOrderedVisibleItems().map { it.props }
        val currIdx = students.indexOfFirst { it.id == currentId }
        val nextIdx = when {
            students.isEmpty() -> null
            currIdx == -1 -> 0
            currIdx == students.size - 1 -> null
            else -> currIdx + 1
        }

        return nextIdx?.let { students[it] }
    }

    fun getPrevStudent(currentId: String): StudentProps? {
        val students = coll.getOrderedVisibleItems().map { it.props }
        val currIdx = students.indexOfFirst { it.id == currentId }
        val nextIdx = when {
            students.isEmpty() -> null
            currIdx == -1 -> 0
            currIdx == 0 -> null
            else -> currIdx - 1
        }

        return nextIdx?.let { students[it] }
    }

}