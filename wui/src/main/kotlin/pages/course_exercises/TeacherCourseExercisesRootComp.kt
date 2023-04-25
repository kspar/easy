package pages.course_exercises

import CONTENT_CONTAINER_ID
import EzDate
import Icons
import UserMessageAction
import cache.BasicCourseInfo
import components.EzCollComp
import components.form.ButtonComp
import components.modal.ConfirmationTextModalComp
import components.modal.Modal
import components.text.StringComp
import dao.CourseExercisesTeacherDAO
import dao.ExerciseDAO
import debug
import getWindowScrollPosition
import kotlinx.coroutines.await
import pages.ExerciseSummaryPage
import pages.Title
import pages.exercise.ExercisePage
import pages.exercise_library.CreateExerciseModalComp
import pages.sidenav.Sidenav
import restore
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.EzSpa
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.unionPromise
import successMessage
import tmRender
import kotlin.js.Promise

class TeacherCourseExercisesRootComp(
    private val courseId: String,
) : Component(null, CONTENT_CONTAINER_ID) {

    data class ExProps(
        val id: String,
        val idx: Int,
        val libTitle: String,
        val titleAlias: String?,
        val isAutoeval: Boolean,
        val deadline: EzDate?,
        val isVisible: Boolean,
        val completed: Int,
        val started: Int,
        val ungraded: Int,
        val unstarted: Int,
        val effectiveTitle: String = titleAlias ?: libTitle,
    )

    private lateinit var courseTitle: String
    private lateinit var coll: EzCollComp<ExProps>

    private lateinit var reorderModal: ReorderCourseExerciseModalComp
    private lateinit var updateTitleAliasModal: UpdateCourseExerciseTitleModalComp
    private lateinit var removeModal: ConfirmationTextModalComp

    private val newExerciseModal = CreateExerciseModalComp(null, courseId, this)

    override val children: List<Component>
        get() = listOf(coll, reorderModal, updateTitleAliasModal, removeModal, newExerciseModal)

    override fun create() = doInPromise {
        val exercisesPromise = CourseExercisesTeacherDAO.getCourseExercises(courseId)
        courseTitle = BasicCourseInfo.get(courseId).await().effectiveTitle
        Title.update { it.parentPageTitle = courseTitle }

        val exercises = exercisesPromise.await()

        val props = exercises.map {
            ExProps(
                it.id, it.ordering_idx, it.library_title, it.title_alias,
                it.grader_type == ExerciseDAO.GraderType.AUTO, it.soft_deadline, it.isVisibleNow,
                it.completed_count, it.started_count, it.ungraded_count, it.unstarted_count
            )
        }

        val items = props.map {
            EzCollComp.Item(
                it,
                if (it.isAutoeval) EzCollComp.ItemTypeIcon(Icons.robot) else EzCollComp.ItemTypeIcon(Icons.teacherFace),
                it.effectiveTitle,
                titleIcon = if (!it.isVisible) EzCollComp.TitleIcon(Icons.hiddenUnf, "Peidetud") else null,
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
                    EzCollComp.Action(
                        if (it.isVisible) Icons.hidden else Icons.visible,
                        if (it.isVisible) "Peida" else "Avalikusta", onActivate = ::showHide
                    ),
                    EzCollComp.Action(Icons.reorder, "Liiguta", onActivate = ::move),
                    EzCollComp.Action(Icons.edit, "Muuda pealkirja", onActivate = ::updateTitleAlias),
                    EzCollComp.Action(Icons.delete, "Eemalda kursuselt", onActivate = ::removeFromCourse)
                ),
            )
        }

        coll = EzCollComp(
            items, EzCollComp.Strings("ülesanne", "ülesannet"),
            massActions = listOf(
                EzCollComp.MassAction(Icons.visible, "Avalikusta", onActivate = { setVisibility(it, true) }),
                EzCollComp.MassAction(Icons.hidden, "Peida", onActivate = { setVisibility(it, false) }),
                EzCollComp.MassAction(Icons.delete, "Eemalda kursuselt", onActivate = ::removeFromCourse),
            ), filterGroups = listOf(), parent = this
        )

        reorderModal = ReorderCourseExerciseModalComp(courseId, this)

        updateTitleAliasModal = UpdateCourseExerciseTitleModalComp(courseId, this)

        removeModal = ConfirmationTextModalComp(
            null, "Eemalda", "Tühista", "Eemaldan...",
            primaryBtnType = ButtonComp.Type.DANGER,
            id = Modal.REMOVE_EXERCISE_FROM_COURSE, parent = this
        )

        Sidenav.replacePageSection(
            Sidenav.PageSection(
                "Ülesanded", listOf(
                    Sidenav.Action(Icons.newExercise, "Uus ülesanne") {
                        val ids = newExerciseModal.openWithClosePromise().await()
                        if (ids != null) {
                            if (ids.courseExerciseId != null) {
                                // was added to course
                                EzSpa.PageManager.navigateTo(ExerciseSummaryPage.link(courseId, ids.courseExerciseId))
                                successMessage { "Ülesanne loodud" }
                            } else {
                                val action = UserMessageAction("Ava ülesandekogus") {
                                    EzSpa.PageManager.navigateTo(ExercisePage.link(ids.exerciseId))
                                }
                                successMessage(action = action) { "Ülesanne loodud" }
                            }
                        }
                    }
                )
            )
        )
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
        "updateTitleAliasModalDst" to updateTitleAliasModal.dstId,
        "newExerciseModalDst" to newExerciseModal.dstId,
    )


    private suspend fun showHide(item: EzCollComp.Item<ExProps>): EzCollComp.Result {
        val nowVisible = !item.props.isVisible
        return setVisibility(listOf(item), nowVisible)
    }

    private suspend fun setVisibility(items: List<EzCollComp.Item<ExProps>>, nowVisible: Boolean): EzCollComp.Result {
        val promises = items.map {
            if (it.props.isVisible != nowVisible) {
                val u = CourseExercisesTeacherDAO.CourseExerciseUpdate(
                    replace = CourseExercisesTeacherDAO.CourseExerciseReplace(isStudentVisible = nowVisible)
                )
                CourseExercisesTeacherDAO.updateCourseExercise(courseId, it.props.id, u)
            } else Promise.resolve(Unit)
        }
        promises.unionPromise().await()
        successMessage { if (nowVisible) "Avalikustatud" else "Peidetud" }
        recreate()
        return EzCollComp.ResultUnmodified
    }

    private suspend fun move(item: EzCollComp.Item<ExProps>): EzCollComp.Result {
        reorderModal.movableExercise =
            ReorderCourseExerciseModalComp.CourseExercise(item.props.id, item.props.effectiveTitle, item.props.idx)
        reorderModal.allExercises = CourseExercisesTeacherDAO.getCourseExercises(courseId).await().map {
            ReorderCourseExerciseModalComp.CourseExercise(it.id, it.effectiveTitle, it.ordering_idx)
        }
        reorderModal.setText(StringComp.boldTriple("Liiguta ", item.props.effectiveTitle, "..."))
        reorderModal.createAndBuild().await()
        val modalReturn = reorderModal.openWithClosePromise().await()
        if (modalReturn != null) {
            recreate()
        }
        return EzCollComp.ResultUnmodified
    }

    private suspend fun updateTitleAlias(item: EzCollComp.Item<ExProps>): EzCollComp.Result {
        updateTitleAliasModal.updatableCourseExercise =
            UpdateCourseExerciseTitleModalComp.CourseExercise(item.props.id, item.props.libTitle, item.props.titleAlias)
        updateTitleAliasModal.createAndBuild().await()
        debug { "start open" }
        val modalReturn = updateTitleAliasModal.openWithClosePromise().await()
        if (modalReturn != null) {
            recreate()
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

    private suspend fun recreate() {
        val s = getWindowScrollPosition()
        createAndBuild().await()
        s.restore()
    }
}