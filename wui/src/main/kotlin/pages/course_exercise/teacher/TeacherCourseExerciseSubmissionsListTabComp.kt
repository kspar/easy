package pages.course_exercise.teacher

import EzDate
import HumanStringComparator
import Icons
import Key
import components.EzCollComp
import components.EzCollConf
import dao.CourseExercisesTeacherDAO
import dao.ParticipantsDAO
import kotlinx.coroutines.await
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import translation.Str

class TeacherCourseExerciseSubmissionsListTabComp(
    private val courseId: String,
    private val courseExId: String,
    private val onOpenStudent: suspend (CourseExercisesTeacherDAO.LatestStudentSubmission) -> Unit,
    private val onStudentListChange: () -> Unit,
    parent: Component
) : Component(parent) {


    private lateinit var coll: EzCollComp<CourseExercisesTeacherDAO.LatestStudentSubmission>

    override val children: List<Component>
        get() = listOf(coll)

    override fun create() = doInPromise {
        val groups = ParticipantsDAO.getCourseGroups(courseId).await()

        val submissions =
            CourseExercisesTeacherDAO.getLatestSubmissions(courseId, courseExId).await().latest_submissions.map {
                EzCollComp.Item(
                    it,
                    EzCollComp.ItemTypeIcon(
                        when {
                            it.submission?.grade == null -> Icons.dotsHorizontal
                            it.submission.grade.is_autograde -> Icons.robot
                            else -> Icons.teacherFace
                        }
                    ),
                    it.name,
                    titleIcon = if (it.submission?.seen == false)
                        EzCollComp.TitleIcon(Icons.notificationDot, Str.newSubmission)
                    else null,
                    titleInteraction = EzCollComp.TitleAction<CourseExercisesTeacherDAO.LatestStudentSubmission> {
                        onOpenStudent(it)
                    },
                    // TODO: paint and icon if time > deadline ?
                    topAttr =
                    if (it.submission != null) {
                        EzCollComp.SimpleAttr(
                            Str.submissionTimeLabel,
                            shortValue = it.submission.time.toHumanString(EzDate.Format.DATE),
                            longValue = it.submission.time.toHumanString(EzDate.Format.FULL),
                        )
                    } else null,
                    // TODO: action and massaction to rerun automatic tests (how to wait on them and update?)
                    actions = buildList {
                        if (it.submission?.seen == true)
                            add(
                                EzCollComp.Action(
                                    Icons.circle, Str.markAsNew,
                                    onActivate = {
                                        CourseExercisesTeacherDAO.setSubmissionSeenStatus(
                                            courseId, courseExId, false, listOf(it.props.submission!!.id)
                                        ).await()
                                        createAndBuild().await()
                                        EzCollComp.ResultUnmodified
                                    }
                                ))
                        if (it.submission?.seen == false)
                            add(
                                EzCollComp.Action(
                                    Icons.circleUnf, Str.markAsSeen,
                                    onActivate = {
                                        CourseExercisesTeacherDAO.setSubmissionSeenStatus(
                                            courseId, courseExId, true, listOf(it.props.submission!!.id)
                                        ).await()
                                        createAndBuild().await()
                                        EzCollComp.ResultUnmodified
                                    }
                                ))

                        it.submission?.id?.let { submissionId ->
                            add(
                                EzCollComp.Action(
                                    Icons.download, Str.downloadSubmission,
                                    onActivate = {
                                        CourseExercisesTeacherDAO.downloadSubmissions(
                                            courseId, courseExId, listOf(submissionId)
                                        ).await()
                                        EzCollComp.ResultUnmodified
                                    }
                                )
                            )
                        }
                    },
                    isSelectable = it.submission != null,
                    progressBar = EzCollComp.ProgressBar(it.status.translateToProgress()),
                )
            }

        coll = EzCollComp(
            submissions,
            strings = EzCollComp.Strings(Str.studentsSingular, Str.studentsPlural),
            filterGroups = listOfNotNull(
                EzCollComp.createGroupFilter(groups),
                EzCollComp.FilterGroup(
                    "Olek", listOf(
                        EzCollComp.Filter(
                            "Automaatselt hinnatud",
                            confType = EzCollConf.TeacherCourseExerciseSubmissionsFilter.STATE_GRADED_AUTO
                        ) { it.props.submission?.grade?.is_autograde == true },
                        EzCollComp.Filter(
                            "Õpetaja hinnatud",
                            confType = EzCollConf.TeacherCourseExerciseSubmissionsFilter.STATE_GRADED_TEACHER
                        ) { it.props.submission?.grade?.is_autograde == false },
                        EzCollComp.Filter(
                            "Hindamata",
                            confType = EzCollConf.TeacherCourseExerciseSubmissionsFilter.STATE_UNGRADED
                        ) { it.props.submission?.grade == null },
                        EzCollComp.Filter(
                            "Esitamata",
                            confType = EzCollConf.TeacherCourseExerciseSubmissionsFilter.STATE_UNSUBMITTED
                        ) { it.props.submission == null },
                    )
                )
            ),
            sorters = buildList {
                add(EzCollComp.Sorter("Nimi",
                    compareBy<EzCollComp.Item<CourseExercisesTeacherDAO.LatestStudentSubmission>, String?>(
                        HumanStringComparator
                    ) {
                        it.props.family_name
                    }.thenBy(HumanStringComparator) { it.props.given_name },
                    confType = EzCollConf.TeacherCourseExerciseSubmissionsSorter.NAME
                )
                )
                add(EzCollComp.Sorter("Punktid",
                    compareBy<EzCollComp.Item<CourseExercisesTeacherDAO.LatestStudentSubmission>> {
                        // nulls last
                        if (it.props.submission?.grade == null) 1 else 0
                    }.thenByDescending { it.props.submission?.grade?.grade }
                        .thenBy(HumanStringComparator) { it.props.family_name }
                        .thenBy(HumanStringComparator) { it.props.given_name },
                    reverseComparator = compareBy<EzCollComp.Item<CourseExercisesTeacherDAO.LatestStudentSubmission>> {
                        // nulls last
                        if (it.props.submission?.grade == null) 1 else 0
                    }.thenBy { it.props.submission?.grade?.grade }
                        .thenBy(HumanStringComparator) { it.props.family_name }
                        .thenBy(HumanStringComparator) { it.props.given_name },
                    confType = EzCollConf.TeacherCourseExerciseSubmissionsSorter.POINTS
                )
                )
                add(EzCollComp.Sorter(Str.submissionTimeLabel,
                    compareBy<EzCollComp.Item<CourseExercisesTeacherDAO.LatestStudentSubmission>> {
                        // nulls last
                        if (it.props.submission?.time == null) 1 else 0
                    }.thenBy { it.props.submission?.time }
                        .thenBy(HumanStringComparator) { it.props.family_name }
                        .thenBy(HumanStringComparator) { it.props.given_name },
                    reverseComparator = compareBy<EzCollComp.Item<CourseExercisesTeacherDAO.LatestStudentSubmission>> {
                        // nulls last
                        if (it.props.submission?.time == null) 1 else 0
                    }.thenByDescending { it.props.submission?.time }
                        .thenBy(HumanStringComparator) { it.props.family_name }
                        .thenBy(HumanStringComparator) { it.props.given_name },
                    confType = EzCollConf.TeacherCourseExerciseSubmissionsSorter.TIME
                )
                )
            },
            massActions = listOf(
                EzCollComp.MassAction(
                    Icons.circle, Str.markAsNew,
                    onActivate = {
                        val subIds = it.mapNotNull { it.props.submission?.id }
                        CourseExercisesTeacherDAO.setSubmissionSeenStatus(courseId, courseExId, false, subIds).await()
                        createAndBuild().await()
                        EzCollComp.ResultUnmodified
                    }
                ),
                EzCollComp.MassAction(
                    Icons.circleUnf, Str.markAsSeen,
                    onActivate = {
                        val subIds = it.mapNotNull { it.props.submission?.id }
                        CourseExercisesTeacherDAO.setSubmissionSeenStatus(courseId, courseExId, true, subIds).await()
                        createAndBuild().await()
                        EzCollComp.ResultUnmodified
                    }
                ),
                EzCollComp.MassAction(
                    Icons.download, Str.downloadSubmission,
                    onActivate = {
                        CourseExercisesTeacherDAO.downloadSubmissions(
                            courseId, courseExId, it.mapNotNull { it.props.submission?.id }
                        ).await()
                        EzCollComp.ResultUnmodified
                    }
                )
            ),
            compact = true,
            userConf = EzCollConf.UserConf.retrieve(Key.TEACHER_COURSE_EXERCISE_SUBMISSIONS_USER_CONF),
            onConfChange = {
                it.store(Key.TEACHER_COURSE_EXERCISE_SUBMISSIONS_USER_CONF, hasCourseGroupFilter = true)
                onStudentListChange()
            },
            parent = this
        )
    }

    fun getNextStudent(currentId: String): CourseExercisesTeacherDAO.LatestStudentSubmission? {
        val students = coll.getOrderedVisibleItems().map { it.props }
        val currIdx = students.indexOfFirst { it.student_id == currentId }
        val nextIdx = when {
            students.isEmpty() -> null
            currIdx == -1 -> 0
            currIdx == students.size - 1 -> null
            else -> currIdx + 1
        }

        return nextIdx?.let { students[it] }
    }

    fun getPrevStudent(currentId: String): CourseExercisesTeacherDAO.LatestStudentSubmission? {
        val students = coll.getOrderedVisibleItems().map { it.props }
        val currIdx = students.indexOfFirst { it.student_id == currentId }
        val nextIdx = when {
            students.isEmpty() -> null
            currIdx == -1 -> 0
            currIdx == 0 -> null
            else -> currIdx - 1
        }

        return nextIdx?.let { students[it] }
    }
}