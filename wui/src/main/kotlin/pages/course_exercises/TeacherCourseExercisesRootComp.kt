package pages.course_exercises

import CONTENT_CONTAINER_ID
import Icons
import cache.BasicCourseInfo
import components.EzCollComp
import dao.CoursesTeacherDAO
import dao.ExerciseDAO
import kotlinx.coroutines.await
import pages.ExerciseSummaryPage
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.sleep
import tmRender
import kotlin.js.Date

class TeacherCourseExercisesRootComp(
    private val courseId: String,
) : Component(null, CONTENT_CONTAINER_ID) {

    data class ExProps(
        val id: String,
        val idx: Int,
        val title: String,
        val isAutoeval: Boolean,
        val deadline: Date?,
        // TODO: service doesn't return
        val isVisible: Boolean,
        val completed: Int,
        val started: Int,
        val ungraded: Int,
        val unstarted: Int,
    )

    private lateinit var courseTitle: String
    private lateinit var coll: EzCollComp<ExProps>

    override val children: List<Component>
        get() = listOf(coll)

    override fun create() = doInPromise {
        val exercisesPromise = CoursesTeacherDAO.getCourseExercises(courseId)
        courseTitle = BasicCourseInfo.get(courseId).await().title
        val exercises = exercisesPromise.await()

        sleep(4000).await()

        val props = exercises.map {
            ExProps(
                it.id, it.ordering_idx, it.effective_title, it.grader_type == ExerciseDAO.GraderType.AUTO,
                it.soft_deadline,
                it.id.toInt() % 3 != 0,
                it.completed_count, it.started_count, it.ungraded_count, it.unstarted_count
            )
        }

        val items = props.map {
            EzCollComp.Item(
                it,
                if (it.isAutoeval) EzCollComp.ItemTypeIcon(Icons.robot) else EzCollComp.ItemTypeIcon(Icons.teacherFace),
                it.title,
                titleIcon = if (!it.isVisible) Icons.hiddenUnf else null,
                titleIconLabel = if (!it.isVisible) "Peidetud" else null,
                titleStatus = if (!it.isVisible) EzCollComp.TitleStatus.INACTIVE else EzCollComp.TitleStatus.NORMAL,
                titleLink = ExerciseSummaryPage.link(courseId, it.id),
                // TODO: date attr
                // TODO: unf icon
                // TODO: editable with datetime picker
//                topAttr = if (it.deadline != null) {
//                    EzCollComp.SimpleAttr("Tähtaeg", it.deadline.toEstonianString(), Icons.pending, )
//                } else null
                progressBar = EzCollComp.ProgressBar(it.completed, it.started, it.ungraded, it.unstarted, true),
                isSelectable = true,
                actions = listOf(),
            )
        }

        coll = EzCollComp(
            items, EzCollComp.Strings("ülesanne", "ülesannet"),
            massActions = listOf(), filterGroups = listOf(), parent = this
        )
    }

    override fun renderLoading() = tmRender(
        "tm-loading-placeholders",
        mapOf("marginTopRem" to 4, "titleWidthRem" to 40)
    )

    override fun render() = tmRender(
        "t-c-course-exercises-teacher",
        "title" to courseTitle,
        "collDst" to coll.dstId
    )
}