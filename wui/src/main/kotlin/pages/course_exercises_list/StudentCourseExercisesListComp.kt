package pages.course_exercises_list

import CONTENT_CONTAINER_ID
import EzDate
import Icons
import cache.BasicCourseInfo
import components.EzCollComp
import dao.CourseExercisesStudentDAO
import dao.ExerciseDAO
import kotlinx.coroutines.await
import pages.course_exercise.ExerciseSummaryPage
import pages.Title
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import template

class StudentCourseExercisesListComp(
    private val courseId: String,
) : Component(null, CONTENT_CONTAINER_ID) {

    data class ExProps(
        val id: String,
        val icon: String,
        val title: String,
        val graderType: ExerciseDAO.GraderType,
        val deadline: EzDate?,
        val status: CourseExercisesStudentDAO.SubmissionStatus,
        val grade: Int?,
        val gradedBy: ExerciseDAO.GraderType?,
        val idx: Int,
    )

    private lateinit var courseTitle: String
    private lateinit var coll: EzCollComp<ExProps>

    override val children: List<Component>
        get() = listOf(coll)

    override fun create() = doInPromise {
        val exercisesPromise = CourseExercisesStudentDAO.getCourseExercises(courseId)
        courseTitle = BasicCourseInfo.get(courseId).await().effectiveTitle
        Title.update { it.parentPageTitle = courseTitle }

        val exercises = exercisesPromise.await()

        val props = exercises.map {
            ExProps(
                it.id, it.icon, it.effective_title, it.grader_type, it.deadline, it.status, it.grade, it.graded_by,
                it.ordering_idx
            )
        }

        val items = props.map {
            EzCollComp.Item(
                it,
                EzCollComp.ItemTypeIcon(it.icon),
                it.title,
                // TODO: badge only if 100 regardless of threshold
                titleIcon = if (it.status == CourseExercisesStudentDAO.SubmissionStatus.COMPLETED)
                    EzCollComp.TitleIcon(
                        """<ez-exercise-badge>${Icons.awardWithCheck}</ez-exercise-badge>""", "Tehtud!"
                    ) else null,
                titleLink = ExerciseSummaryPage.link(courseId, it.id),
                topAttr = if (it.deadline != null) {
                    if (it.deadline.isSoonerThanHours(24) &&
                        (it.status == CourseExercisesStudentDAO.SubmissionStatus.STARTED ||
                                it.status == CourseExercisesStudentDAO.SubmissionStatus.UNSTARTED)
                    ) {
                        EzCollComp.RenderedAttr(
                            "Tähtaeg", it.deadline, {
                                """<ez-deadline-close>${it.toHumanString(EzDate.Format.FULL)}</ez-deadline-close>"""
                            }, """<ez-deadline-close>${Icons.alarmClock}</ez-deadline-close>""",
                            { it.toHumanString(EzDate.Format.FULL) }
                        )
                    } else {
                        EzCollComp.SimpleAttr("Tähtaeg", it.deadline.toHumanString(EzDate.Format.FULL), Icons.pending)
                    }
                } else null,
                progressBar = EzCollComp.ProgressBar(translateStatusToProgress(it.status)),
            )
        }

        coll = EzCollComp(
            items, EzCollComp.Strings("ülesanne", "ülesannet"),
            parent = this
        )
    }

    override fun render() = template(
        """
            <div class="title-wrap no-crumb">
                <h2 class="title">{{title}}</h2>
            </div>
            <ez-dst id="{{collDst}}"></ez-dst>
        """.trimIndent(),
        "title" to courseTitle,
        "collDst" to coll.dstId,
    )


    private fun translateStatusToProgress(status: CourseExercisesStudentDAO.SubmissionStatus) =
        when (status) {
            CourseExercisesStudentDAO.SubmissionStatus.COMPLETED -> EzCollComp.Progress(1, 0, 0, 0)
            CourseExercisesStudentDAO.SubmissionStatus.STARTED -> EzCollComp.Progress(0, 1, 0, 0)
            CourseExercisesStudentDAO.SubmissionStatus.UNGRADED -> EzCollComp.Progress(0, 0, 1, 0)
            CourseExercisesStudentDAO.SubmissionStatus.UNSTARTED -> EzCollComp.Progress(0, 0, 0, 1)
        }
}