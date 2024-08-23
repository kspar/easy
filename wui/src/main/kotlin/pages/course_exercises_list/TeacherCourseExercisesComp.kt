package pages.course_exercises_list

import CONTENT_CONTAINER_ID
import EzDate
import Icons
import Key
import LocalStore
import cache.BasicCourseInfo
import components.ButtonComp
import components.EzCollComp
import components.EzCollConf
import components.ToastThing
import components.modal.ConfirmationTextModalComp
import components.text.StringComp
import dao.CourseExercisesTeacherDAO
import dao.ExerciseDAO
import dao.ParticipantsDAO
import debug
import getWindowScrollPosition
import kotlinx.coroutines.await
import pages.Title
import pages.course_exercise.ExerciseSummaryPage
import pages.exercise_in_library.ExercisePage
import pages.exercise_library.CreateExerciseModalComp
import pages.sidenav.Sidenav
import restore
import rip.kspar.ezspa.*
import successMessage
import template
import tmRender
import translation.Str
import kotlin.js.Promise

class TeacherCourseExercisesComp(
    private val courseId: String,
) : Component(null, CONTENT_CONTAINER_ID) {

    data class ExProps(
        val id: String,
        val idx: Int,
        val libTitle: String,
        val titleAlias: String?,
        val isAutoeval: Boolean,
        val threshold: Int,
        val deadline: EzDate?,
        val closingTime: EzDate?,
        val isVisible: Boolean,
        val visibleFrom: EzDate?,
        val completed: Int,
        val started: Int,
        val ungraded: Int,
        val unstarted: Int,
        override val groups: List<ParticipantsDAO.CourseGroup>,
        val effectiveTitle: String = titleAlias ?: libTitle,
    ) : EzCollComp.WithGroups

    private var groupId: String? = null

    private lateinit var courseTitle: String
    private lateinit var coll: EzCollComp<ExProps>

    private lateinit var reorderModal: ReorderCourseExerciseModalComp
    private lateinit var removeModal: ConfirmationTextModalComp

    private val updateModalDst = IdGenerator.nextId()
    private var updateModal: UpdateCourseExerciseModalComp? = null

    private val newExerciseModal = CreateExerciseModalComp(null, courseId, this)

    override val children: List<Component>
        get() = listOfNotNull(coll, reorderModal, updateModal, removeModal, newExerciseModal)

    override fun create() = doInPromise {
        groupId = LocalStore.get(Key.TEACHER_SELECTED_GROUP)?.let {
            if (it == LocalStore.TEACHER_SELECTED_GROUP_NONE_ID) null else it
        }

        val exercisesPromise = CourseExercisesTeacherDAO.getCourseExercises(courseId, groupId)
        val groups = ParticipantsDAO.getCourseGroups(courseId).await()
        courseTitle = BasicCourseInfo.get(courseId).await().effectiveTitle
        Title.update { it.parentPageTitle = courseTitle }

        val exercises = exercisesPromise.await()

        val props = exercises.map {
            ExProps(
                it.course_exercise_id, it.ordering_idx, it.library_title, it.title_alias,
                it.grader_type == ExerciseDAO.GraderType.AUTO, it.grade_threshold,
                it.soft_deadline, it.hard_deadline, it.isVisibleNow, it.student_visible_from,
                it.completed_count, it.started_count, it.ungraded_count, it.unstarted_count,
                // all exercises are currently visible to all groups
                groups
            )
        }

        val items = props.map {
            EzCollComp.Item(
                it,
                if (it.isAutoeval) EzCollComp.ItemTypeIcon(Icons.robot) else EzCollComp.ItemTypeIcon(Icons.teacherFace),
                it.effectiveTitle,
                titleIcon = if (!it.isVisible) EzCollComp.TitleIcon(Icons.hiddenUnf, Str.hidden) else null,
                titleStatus = if (!it.isVisible) EzCollComp.TitleStatus.INACTIVE else EzCollComp.TitleStatus.NORMAL,
                titleInteraction = EzCollComp.TitleLink(ExerciseSummaryPage.link(courseId, it.id)),
                // TODO: editable, open exercise settings
                topAttr = if (it.deadline != null) {
                    EzCollComp.SimpleAttr(
                        Str.deadlineLabel,
                        it.deadline.toHumanString(EzDate.Format.FULL),
                        Icons.pending
                    )
                } else null,
                progressBar = EzCollComp.ProgressBar(it.completed, it.started, it.ungraded, it.unstarted, true),
                isSelectable = true,
                actions = listOf(
                    EzCollComp.Action(
                        if (it.isVisible) Icons.hidden else Icons.visible,
                        if (it.isVisible) Str.doHide else Str.doReveal, onActivate = ::showHide
                    ),
                    EzCollComp.Action(Icons.reorder, Str.doMove, onActivate = ::move),
                    EzCollComp.Action(Icons.settings, Str.exerciseSettings, onActivate = ::updateCourseExercise),
                    EzCollComp.Action(Icons.delete, Str.doRemoveFromCourse, onActivate = ::removeFromCourse)
                ),
            )
        }

        coll = EzCollComp(
            items, EzCollComp.Strings(Str.exerciseSingular, Str.exercisePlural),
            massActions = listOf(
                EzCollComp.MassAction(Icons.visible, Str.doReveal, onActivate = { setVisibility(it, true) }),
                EzCollComp.MassAction(Icons.hidden, Str.doHide, onActivate = { setVisibility(it, false) }),
                EzCollComp.MassAction(Icons.delete, Str.doRemoveFromCourse, onActivate = ::removeFromCourse),
            ),
            filterGroups = listOfNotNull(
                EzCollComp.createGroupFilter(groups, noGroupOption = false),
                EzCollComp.FilterGroup(
                    "Olek", listOf(
                        EzCollComp.Filter(
                            "Hindamata esitused",
                            confType = EzCollConf.TeacherCourseExercisesFilter.STATE_HAS_UNGRADED
                        ) { it.props.ungraded > 0 },
                        EzCollComp.Filter(
                            "Nähtavad ülesanded",
                            confType = EzCollConf.TeacherCourseExercisesFilter.STATE_VISIBLE
                        ) { it.props.isVisible },
                        EzCollComp.Filter(
                            "Tähtaeg tulevikus",
                            confType = EzCollConf.TeacherCourseExercisesFilter.STATE_DEADLINE_FUTURE
                        ) { it.props.deadline != null && it.props.deadline > EzDate.now() },
                        EzCollComp.Filter(
                            "Tähtaeg möödas",
                            confType = EzCollConf.TeacherCourseExercisesFilter.STATE_DEADLINE_PAST
                        ) { it.props.deadline != null && it.props.deadline < EzDate.now() },
                    )
                ),
            ),
            userConf = EzCollConf.UserConf.retrieve(Key.TEACHER_COURSE_EXERCISES_USER_CONF),
            onConfChange = {
                it.store(Key.TEACHER_COURSE_EXERCISES_USER_CONF, hasCourseGroupFilter = true)
                if (it.globalGroupFilter?.id != groupId) {
                    // group changed
                    createAndBuild().await()
                }
            },
            parent = this
        )

        reorderModal = ReorderCourseExerciseModalComp(courseId, this)

        removeModal = ConfirmationTextModalComp(
            null, Str.doRemove, Str.cancel, Str.removing,
            primaryBtnType = ButtonComp.Type.FILLED_DANGER, parent = this
        )

        Sidenav.replacePageSection(
            Sidenav.PageSection(
                Str.exercises, listOf(
                    Sidenav.Action(Icons.newExercise, Str.newExercise) {
                        val ids = newExerciseModal.openWithClosePromise().await()
                        if (ids != null) {
                            if (ids.courseExerciseId != null) {
                                // was added to course
                                EzSpa.PageManager.navigateTo(ExerciseSummaryPage.link(courseId, ids.courseExerciseId))
                                ToastThing(Str.msgExerciseCreated)
                            } else {
                                ToastThing(
                                    Str.msgExerciseCreated,
                                    ToastThing.Action(
                                        Str.openInLib,
                                        { EzSpa.PageManager.navigateTo(ExercisePage.link(ids.exerciseId)) })
                                )
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

    override fun render() = template(
        """
            <div class="title-wrap no-crumb">
                <h2 class="title">{{title}}</h2>
            </div>
            <ez-dst id="{{collDst}}"></ez-dst>
            <ez-dst id="{{reorderModalDst}}"></ez-dst>
            <ez-dst id="{{updateModalDst}}"></ez-dst>
            <ez-dst id="{{newExerciseModalDst}}"></ez-dst>
        """.trimIndent(),
        "title" to courseTitle,
        "collDst" to coll.dstId,
        "reorderModalDst" to reorderModal.dstId,
        "updateModalDst" to updateModalDst,
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
        successMessage { if (nowVisible) Str.revealed else Str.hidden }
        recreate()
        return EzCollComp.ResultUnmodified
    }

    private suspend fun move(item: EzCollComp.Item<ExProps>): EzCollComp.Result {
        reorderModal.movableExercise =
            ReorderCourseExerciseModalComp.CourseExercise(item.props.id, item.props.effectiveTitle, item.props.idx)
        reorderModal.allExercises = CourseExercisesTeacherDAO.getCourseExercises(courseId).await().map {
            ReorderCourseExerciseModalComp.CourseExercise(it.course_exercise_id, it.effectiveTitle, it.ordering_idx)
        }
        reorderModal.setText(StringComp.boldTriple(Str.doMove + " ", item.props.effectiveTitle, "..."))
        reorderModal.createAndBuild().await()
        val modalReturn = reorderModal.openWithClosePromise().await()
        if (modalReturn != null) {
            recreate()
        }
        return EzCollComp.ResultUnmodified
    }

    private suspend fun updateCourseExercise(item: EzCollComp.Item<ExProps>): EzCollComp.Result {
        val m = UpdateCourseExerciseModalComp(
            courseId,
            UpdateCourseExerciseModalComp.CourseExercise(
                item.props.id,
                item.props.libTitle,
                item.props.titleAlias,
                item.props.threshold,
                item.props.isVisible,
                item.props.visibleFrom,
                item.props.deadline,
                item.props.closingTime,
            ),
            this,
            dstId = updateModalDst
        )

        updateModal = m

        m.createAndBuild().await()
        val modalReturn = m.openWithClosePromise().await()
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
        val submissionWarning = if (subCount > 0) Str.submissionsWillBeDeleted else ""

        val text = if (items.size == 1) {
            val item = items[0]
            StringComp.boldTriple(Str.removeExercise + " ", item.title, "? $submissionWarning")
        } else {
            StringComp.boldTriple(
                Str.doRemove + " ",
                items.size.toString(),
                " ${Str.removeExercisesPlural}? $submissionWarning"
            )
        }

        removeModal.setText(text)
        removeModal.primaryAction = {
            debug { "Remove confirmed" }

            items.forEach {
                CourseExercisesTeacherDAO.removeExerciseFromCourse(courseId, it.props.id).await()
            }

            successMessage { Str.removed }

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