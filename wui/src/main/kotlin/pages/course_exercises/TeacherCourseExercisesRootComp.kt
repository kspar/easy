package pages.course_exercises

import CONTENT_CONTAINER_ID
import Icons
import cache.BasicCourseInfo
import components.EzCollComp
import components.StringComp
import components.form.ButtonComp
import components.modal.ConfirmationTextModalComp
import components.modal.Modal
import dao.CourseExercisesTeacherDAO
import dao.ExerciseDAO
import debug
import getWindowScrollPosition
import kotlinx.coroutines.await
import pages.ExerciseSummaryPage
import restoreWindowScroll
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import successMessage
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
    private lateinit var removeModal: ConfirmationTextModalComp
    private lateinit var reorderModal: ReorderCourseExerciseModalComp

    override val children: List<Component>
        get() = listOf(coll, removeModal, reorderModal)

    override fun create() = doInPromise {
        val exercisesPromise = CourseExercisesTeacherDAO.getCourseExercises(courseId)
        courseTitle = BasicCourseInfo.get(courseId).await().title
        val exercises = exercisesPromise.await()

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
                actions = listOf(
                    EzCollComp.Action(Icons.reorder, "Liiguta", onActivate = ::move),
                    EzCollComp.Action(Icons.delete, "Eemalda kursuselt", onActivate = ::removeFromCourse)
                ),
            )
        }

        coll = EzCollComp(
            items, EzCollComp.Strings("ülesanne", "ülesannet"),
            massActions = listOf(
                EzCollComp.MassAction(Icons.delete, "Eemalda kursuselt", onActivate = ::removeFromCourse)
            ), filterGroups = listOf(), parent = this
        )

        removeModal = ConfirmationTextModalComp(
            null, "Eemalda", "Tühista", "Eemaldan...",
            primaryBtnType = ButtonComp.Type.DANGER,
            id = Modal.REMOVE_EXERCISE_FROM_COURSE, parent = this
        )

        reorderModal = ReorderCourseExerciseModalComp(courseId, this)
    }

    override fun renderLoading() = tmRender(
        "tm-loading-placeholders",
        mapOf("marginTopRem" to 4, "titleWidthRem" to 40)
    )

    override fun render() = tmRender(
        "t-c-course-exercises-teacher",
        "title" to courseTitle,
        "collDst" to coll.dstId,
        "reorderModalDst" to reorderModal.dstId,
    )

    private suspend fun move(item: EzCollComp.Item<ExProps>): EzCollComp.Result {
        reorderModal.movableExercise =
            ReorderCourseExerciseModalComp.CourseExercise(item.props.id, item.props.title, item.props.idx)
        reorderModal.allExercises = CourseExercisesTeacherDAO.getCourseExercises(courseId).await().map {
            ReorderCourseExerciseModalComp.CourseExercise(it.id, it.effective_title, it.ordering_idx)
        }
        reorderModal.setText(StringComp.boldTriple("Liiguta ", item.props.title, "..."))
        reorderModal.createAndBuild().await()
        val modalReturn = reorderModal.openWithClosePromise().await()
        if (modalReturn != null) {
            val s = getWindowScrollPosition()
            createAndBuild().await()
            restoreWindowScroll(s)
        }
        return EzCollComp.ResultUnmodified
    }

    private suspend fun removeFromCourse(item: EzCollComp.Item<ExProps>): EzCollComp.Result =
        removeFromCourse(listOf(item))

    private suspend fun removeFromCourse(items: List<EzCollComp.Item<ExProps>>): EzCollComp.Result {
        debug { "Removing exercises ${items.map { it.title }}?" }

        val subCount = items.sumOf { it.props.completed + it.props.started + it.props.ungraded }
        val submissionWarning = if (subCount > 0) "Õpilaste esitused kustutatakse." else ""

        val text = if (items.size == 1) {
            val item = items[0]
            StringComp.boldTriple("Eemalda ülesanne ", item.title, "? $submissionWarning")
        } else {
            StringComp.boldTriple("Eemalda ", items.size.toString(), " ülesannet? $submissionWarning")
        }

        removeModal.setText(text)
        removeModal.primaryAction = {
            debug { "Remove confirmed" }

            items.forEach {
                CourseExercisesTeacherDAO.removeExerciseFromCourse(courseId, it.props.id).await()
            }

            successMessage { "Eemaldatud" }

            true
        }

        val removed = removeModal.openWithClosePromise().await()

        return if (removed)
            EzCollComp.ResultModified<ExProps>(emptyList())
        else {
            debug { "Remove cancelled" }
            EzCollComp.ResultUnmodified
        }
    }
}