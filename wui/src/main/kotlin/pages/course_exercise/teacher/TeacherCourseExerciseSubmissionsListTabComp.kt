package pages.course_exercise.teacher

import AppProperties
import EzDate
import HumanStringComparator
import Icons
import components.ezcoll.EzCollComp
import components.ezcoll.EzCollConf
import copyToClipboard
import dao.CourseExercisesTeacherDAO
import dao.ParticipantsDAO
import kotlinx.coroutines.await
import pages.course_exercise.ExerciseSummaryPage
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import storage.Key
import storage.LocalStore
import storage.getSavedGroupId
import translation.Str

class TeacherCourseExerciseSubmissionsListTabComp(
    private val courseId: String,
    private val courseExId: String,
    private val onOpenStudent: suspend (CourseExercisesTeacherDAO.LatestStudentSubmission) -> Unit,
    private val onStudentListChange: () -> Unit,
) : Component() {

    private var groupId: String? = null

    private lateinit var coll: EzCollComp<CourseExercisesTeacherDAO.LatestStudentSubmission>

    override val children: List<Component>
        get() = listOf(coll)

    override fun create() = doInPromise {
        groupId = getSavedGroupId(courseId)?.let {
            if (it == LocalStore.TEACHER_SELECTED_GROUP_NONE_ID) null else it
        }

        val groups = ParticipantsDAO.getCourseGroups(courseId).await()

        val submissions =
            CourseExercisesTeacherDAO.getLatestSubmissions(courseId, courseExId, groupId)
                .await().latest_submissions.map {
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

                            if (it.submission != null) {
                                add(
                                    EzCollComp.Action(
                                        Icons.copy, Str.copySubmissionLink,
                                        onActivate = {
                                            copyToClipboard(
                                                AppProperties.WUI_ROOT + ExerciseSummaryPage.link(
                                                    courseId, courseExId, it.props.student_id
                                                )
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
                    Str.state, listOf(
                        EzCollComp.Filter(
                            Str.gradedAutomatically,
                            confType = EzCollConf.TeacherCourseExerciseSubmissionsFilter.STATE_GRADED_AUTO
                        ) { it.props.submission?.grade?.is_autograde == true },
                        EzCollComp.Filter(
                            Str.gradedByTeacher,
                            confType = EzCollConf.TeacherCourseExerciseSubmissionsFilter.STATE_GRADED_TEACHER
                        ) { it.props.submission?.grade?.is_autograde == false },
                        EzCollComp.Filter(
                            Str.notGradedYet,
                            confType = EzCollConf.TeacherCourseExerciseSubmissionsFilter.STATE_UNGRADED
                        ) { it.props.submission?.grade == null },
                        EzCollComp.Filter(
                            Str.notSubmitted,
                            confType = EzCollConf.TeacherCourseExerciseSubmissionsFilter.STATE_UNSUBMITTED
                        ) { it.props.submission == null },
                    )
                )
            ),
            sorters = buildList {
                add(
                    EzCollComp.Sorter(Str.name,
                        compareBy<EzCollComp.Item<CourseExercisesTeacherDAO.LatestStudentSubmission>, String?>(
                            HumanStringComparator
                        ) {
                            it.props.family_name
                        }.thenBy(HumanStringComparator) { it.props.given_name },
                        confType = EzCollConf.TeacherCourseExerciseSubmissionsSorter.NAME
                    )
                )
                add(
                    EzCollComp.Sorter(Str.points,
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
                add(
                    EzCollComp.Sorter(Str.submissionTimeLabel,
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
            userConf = EzCollConf.UserConf.retrieve(Key.TEACHER_COURSE_EXERCISE_SUBMISSIONS_USER_CONF, courseId),
            onConfChange = {
                it.store(Key.TEACHER_COURSE_EXERCISE_SUBMISSIONS_USER_CONF, courseId, hasCourseGroupFilter = true)
                if (getSavedGroupId(courseId) != groupId) {
                    // group changed
                    createAndBuild().await()
                }
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