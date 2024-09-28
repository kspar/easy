package pages.course_exercises_list

import CONTENT_CONTAINER_ID
import EzDate
import Icons
import cache.BasicCourseInfo
import components.ezcoll.EzCollComp
import components.ezcoll.EzCollConf
import dao.CourseExercisesStudentDAO
import kotlinx.coroutines.await
import pages.Title
import pages.course_exercise.ExerciseSummaryPage
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import storage.Key
import template
import translation.Str

class StudentCourseExercisesListComp(
    private val courseId: String,
) : Component(null, CONTENT_CONTAINER_ID) {

    private lateinit var courseTitle: String
    private lateinit var coll: EzCollComp<CourseExercisesStudentDAO.Exercise>

    override val children: List<Component>
        get() = listOf(coll)

    override fun create() = doInPromise {
        val exercisesPromise = CourseExercisesStudentDAO.getCourseExercises(courseId)
        courseTitle = BasicCourseInfo.get(courseId).await().effectiveTitle
        Title.update { it.parentPageTitle = courseTitle }

        val items = exercisesPromise.await().map {
            EzCollComp.Item(
                it,
                EzCollComp.ItemTypeIcon(it.icon),
                it.effective_title,
                titleIcon = if (it.grade?.grade == 100)
                    EzCollComp.TitleIcon(
                        """<ez-exercise-badge>${Icons.awardWithCheck}</ez-exercise-badge>""", Str.completedBadgeLabel
                    ) else null,
                titleInteraction = EzCollComp.TitleLink(ExerciseSummaryPage.link(courseId, it.id)),
                topAttr = if (it.deadline != null) {
                    if (it.deadline.isSoonerThanHours(24) &&
                        it.is_open &&
                        (it.status == CourseExercisesStudentDAO.SubmissionStatus.STARTED ||
                                it.status == CourseExercisesStudentDAO.SubmissionStatus.UNSTARTED)
                    ) {
                        EzCollComp.RenderedAttr(
                            Str.deadline, it.deadline,
                            renderShortValue = {
                                """<ez-deadline-close>${it.toHumanString(EzDate.Format.FULL)}</ez-deadline-close>"""
                            },
                            shortValuePrefix = """<ez-deadline-close>${Icons.alarmClock}</ez-deadline-close>""",
                            renderLongValue = { it.toHumanString(EzDate.Format.FULL) }
                        )
                    } else {
                        EzCollComp.SimpleAttr(
                            Str.deadline,
                            it.deadline.toHumanString(EzDate.Format.FULL),
                            Icons.pending
                        )
                    }
                } else null,
                progressBar = EzCollComp.ProgressBar(it.status.translateToProgress()),
            )
        }

        coll = EzCollComp(
            items, EzCollComp.Strings(Str.exerciseSingular, Str.exercisePlural),
            filterGroups = listOf(
                EzCollComp.FilterGroup(
                    Str.state, listOf(
                        EzCollComp.Filter(
                            Str.studentMySubmissionNotDone,
                            confType = EzCollConf.StudentCourseExercisesFilter.STATE_NOT_DONE
                        ) {
                            (it.props.status == CourseExercisesStudentDAO.SubmissionStatus.UNSTARTED ||
                                    it.props.status == CourseExercisesStudentDAO.SubmissionStatus.STARTED) &&
                                    it.props.is_open
                        },
                        EzCollComp.Filter(
                            Str.studentMySubmissionDone,
                            confType = EzCollConf.StudentCourseExercisesFilter.STATE_DONE
                        ) {
                            it.props.status == CourseExercisesStudentDAO.SubmissionStatus.COMPLETED ||
                                    it.props.status == CourseExercisesStudentDAO.SubmissionStatus.UNGRADED ||
                                    !it.props.is_open
                        },
                    )
                )
            ),
            userConf = EzCollConf.UserConf.retrieve(Key.STUDENT_COURSE_EXERCISES_USER_CONF),
            onConfChange = { it.store(Key.STUDENT_COURSE_EXERCISES_USER_CONF) },
            parent = this
        )
    }

    override fun render() = template(
        """
            <div class="title-wrap no-crumb">
                <h2 class="title">{{title}}</h2>
            </div>
            $coll
        """.trimIndent(),
        "title" to courseTitle,
    )
}