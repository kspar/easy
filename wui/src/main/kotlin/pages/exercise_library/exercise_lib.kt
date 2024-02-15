package pages.exercise_library

import EzDate
import HumanStringComparator
import Icons
import components.BreadcrumbsComp
import components.Crumb
import components.EzCollComp
import components.ToastThing
import components.form.OldButtonComp
import components.modal.ConfirmationTextModalComp
import components.text.StringComp
import dao.ExerciseDAO
import dao.LibraryDAO
import dao.LibraryDirDAO
import debug
import kotlinx.coroutines.await
import pages.exercise_in_library.AddToCourseModalComp
import pages.exercise_in_library.ExercisePage
import pages.exercise_library.permissions_modal.PermissionsModalComp
import pages.sidenav.Sidenav
import queries.HandledResponseError
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.EzSpa
import rip.kspar.ezspa.doInPromise
import successMessage
import translation.Str


class ExerciseLibComp(
    private val dirId: String?,
    private val setPathSuffix: (String) -> Unit,
    dstId: String
) : Component(null, dstId) {

    abstract class Props(
        open val dirId: String,
        open var title: String,
        open val access: DirAccess,
        open val isShared: Boolean,
        val type: Int,
    )

    data class ExerciseProps(
        val exerciseId: String,
        override val dirId: String,
        override var title: String,
        override val access: DirAccess,
        override val isShared: Boolean,
        val graderType: ExerciseDAO.GraderType,
        val coursesCount: Int,
        val modifiedAt: EzDate,
        val modifiedBy: String,
    ) : Props(dirId, title, access, isShared, 1)

    data class DirProps(
        override val dirId: String,
        override var title: String,
        override val access: DirAccess,
        override val isShared: Boolean,
    ) : Props(dirId, title, access, isShared, 0)

    private lateinit var breadcrumbs: BreadcrumbsComp
    private lateinit var ezcoll: EzCollComp<Props>
    private val addToCourseModal = AddToCourseModalComp(emptyList(), "", this)
    private val updateDirModal = UpdateDirModalComp(this)
    private val confirmDeleteDirModal = ConfirmationTextModalComp(
        null, Str.doDelete, Str.cancel, Str.deleting, primaryBtnType = OldButtonComp.Type.DANGER, parent = this
    )
    private val confirmDeleteExerciseModal = ConfirmationTextModalComp(
        null, Str.doDelete, Str.cancel, Str.deleting, primaryBtnType = OldButtonComp.Type.DANGER, parent = this
    )
    private lateinit var currentDirPermissionsModal: PermissionsModalComp
    private val itemPermissionsModal = PermissionsModalComp(currentDirId = dirId, parent = this)
    private val newExerciseModal = CreateExerciseModalComp(dirId, null, this)
    private val newDirModal = CreateDirModalComp(dirId, this)

    override val children: List<Component>
        get() = listOf(
            breadcrumbs, ezcoll, addToCourseModal, updateDirModal, confirmDeleteDirModal, confirmDeleteExerciseModal,
            currentDirPermissionsModal, itemPermissionsModal,
            newExerciseModal, newDirModal
        )

    override fun create() = doInPromise {

        val libRespPromise = LibraryDAO.getLibraryContent(dirId)

        breadcrumbs = if (dirId != null) {
            val parents = LibraryDirDAO.getDirParents(dirId).await().reversed()
            // Current dir must exist in response if dirId != null
            val currentDir = libRespPromise.await().current_dir!!

            setPathSuffix(createPathChainSuffix(parents.map { it.name } + currentDir.name))

            BreadcrumbsComp(createDirChainCrumbs(parents, currentDir.name), this)

        } else {
            BreadcrumbsComp(listOf(Crumb(Str.exerciseLibrary)), this)
        }

        val libResp = libRespPromise.await()

        // Set dirId to null at first because the user might not have M access, so we don't want to try to load permissions
        currentDirPermissionsModal = PermissionsModalComp(null, true, dirId, libResp.current_dir?.name.orEmpty(), this)

        val currentDirAccess = libResp.current_dir?.effective_access
        // can do anything only if current dir is root, or we have at least PRA
        if (currentDirAccess == null || currentDirAccess >= DirAccess.PRA) {
            Sidenav.replacePageSection(
                Sidenav.PageSection(
                    libResp.current_dir?.name ?: Str.exerciseLibrary,

                    buildList {
                        add(Sidenav.Action(Icons.newExercise, Str.newExercise) {
                            val ids = newExerciseModal.openWithClosePromise().await()
                            if (ids != null) {
                                EzSpa.PageManager.navigateTo(ExercisePage.link(ids.exerciseId))
                                ToastThing(Str.msgExerciseCreated)
                            }
                        })
                        add(Sidenav.Action(Icons.newDirectory, Str.newDirectory) {
                            if (newDirModal.openWithClosePromise().await() != null)
                                createAndBuild().await()
                        })

                        if (currentDirAccess == DirAccess.PRAWM) {
                            add(Sidenav.Action(Icons.addPerson, Str.share) {
                                // Set dirId here to avoid loading permissions if user has no M access
                                currentDirPermissionsModal.dirId = dirId
                                val permissionsChanged = currentDirPermissionsModal.refreshAndOpen().await()
                                debug { "Permissions changed: $permissionsChanged" }
                                if (permissionsChanged) {
                                    successMessage { Str.permissionsChanged }
                                    createAndBuild().await()
                                }
                            })
                        }
                    }
                )
            )
        }

        val exerciseProps = libResp.child_exercises.map {
            ExerciseProps(
                it.exercise_id, it.dir_id, it.title,
                it.effective_access, it.is_shared, it.grader_type, it.courses_count,
                it.modified_at, it.modified_by,
            )
        }

        val dirProps = libResp.child_dirs.map {
            DirProps(it.id, it.name, it.effective_access, it.is_shared)
        }

        val items: List<EzCollComp.Item<Props>> = exerciseProps.map { p ->
            EzCollComp.Item<Props>(
                p,
                if (p.graderType == ExerciseDAO.GraderType.AUTO)
                    EzCollComp.ItemTypeIcon(Icons.robot)
                else
                    EzCollComp.ItemTypeIcon(Icons.teacherFace),
                p.title,
                titleIcon = if (p.isShared) EzCollComp.TitleIcon(Icons.teacher, Str.shared) else null,
                titleInteraction = EzCollComp.TitleLink(ExercisePage.link(p.exerciseId)),
                topAttr = EzCollComp.SimpleAttr(
                    Str.modifiedAt,
                    "${p.modifiedAt.toHumanString(EzDate.Format.DATE)} · ${p.modifiedBy}",
                    longValue = "${p.modifiedAt.toHumanString(EzDate.Format.FULL)} · ${p.modifiedBy}"
                ),
                bottomAttrs = listOf(
                    EzCollComp.SimpleAttr(
                        Str.libUsedOnCourses1,
                        "${p.coursesCount} ${Str.libUsedOnCourses2}",
                        Icons.courses
                    ),
                ),
                isSelectable = true,
                actions = buildList {
                    add(EzCollComp.Action(Icons.add, Str.addToCourse, onActivate = ::addToCourse))
                    if (p.access == DirAccess.PRAWM) {
                        add(EzCollComp.Action(Icons.addPerson, Str.share, onActivate = ::permissions))
                        add(EzCollComp.Action(Icons.delete, Str.doDelete, onActivate = ::deleteExercise))
                    }
                },
            )
        } + dirProps.map { p ->
            EzCollComp.Item<Props>(
                p,
                EzCollComp.ItemTypeIcon(if (p.isShared) Icons.sharedFolder else Icons.library),
                p.title,
                titleInteraction = EzCollComp.TitleLink(ExerciseLibraryPage.linkToDir(p.dirId)),
                isSelectable = false,
                actions = buildList {
                    if (p.access >= DirAccess.PRAW)
                        add(EzCollComp.Action(Icons.edit, Str.dirSettings, onActivate = ::dirSettings))
                    if (p.access == DirAccess.PRAWM) {
                        add(EzCollComp.Action(Icons.addPerson, Str.share, onActivate = ::permissions))
                        add(EzCollComp.Action(Icons.delete, Str.doDelete, onActivate = ::deleteDir))
                    }
                },
            )
        }

        ezcoll = EzCollComp<Props>(
            items, EzCollComp.Strings(Str.itemSingular, Str.itemPlural),
            massActions = listOf(
                EzCollComp.MassAction<Props>(Icons.add, Str.addToCourse, ::addToCourse)
            ),
            filterGroups = listOf(
                EzCollComp.FilterGroup<Props>(
                    Str.share, listOf(
                        EzCollComp.Filter(Str.shared) { it.props.isShared },
                        EzCollComp.Filter(Str.private) { !it.props.isShared },
                    )
                ),
                EzCollComp.FilterGroup<Props>(
                    Str.grading, listOf(
                        EzCollComp.Filter(Str.gradingAuto) {
                            it.props is ExerciseProps && it.props.graderType == ExerciseDAO.GraderType.AUTO
                        },
                        EzCollComp.Filter(Str.gradingTeacher) {
                            it.props is ExerciseProps && it.props.graderType == ExerciseDAO.GraderType.TEACHER
                        },
                    )
                ),
            ),
            sorters = listOf(
                EzCollComp.Sorter<Props>(Str.sortByName,
                    compareBy<EzCollComp.Item<Props>> {
                        if (it.props is ExerciseProps) 1 else 0
                    }.thenBy(HumanStringComparator) {
                        it.props.title
                    }
                ),
                EzCollComp.Sorter(Str.sortByModified,
                    compareByDescending<EzCollComp.Item<Props>> {
                        if (it.props is ExerciseProps) it.props.modifiedAt else EzDate.future()
                    }.thenBy(HumanStringComparator) {
                        it.props.title
                    }
                ),
                EzCollComp.Sorter<Props>(Str.sortByPopularity,
                    compareByDescending<EzCollComp.Item<Props>> {
                        if (it.props is ExerciseProps) it.props.coursesCount else Int.MAX_VALUE
                    }.thenBy(HumanStringComparator) {
                        it.props.title
                    }
                ),
            ),
            parent = this
        )
    }

    private suspend fun addToCourse(item: EzCollComp.Item<Props>) = addToCourse(listOf(item))

    private suspend fun addToCourse(items: List<EzCollComp.Item<Props>>): EzCollComp.Result {
        if (items.size == 1) {
            val item = items.single()
            item.props as ExerciseProps
            addToCourseModal.setSingleExercise(item.props.exerciseId, item.props.title)
        } else {
            addToCourseModal.setMultipleExercises(
                items.map {
                    it.props as ExerciseProps
                    it.props.exerciseId
                }
            )
        }
        return openAddToCourse()
    }

    private suspend fun openAddToCourse(): EzCollComp.Result {
        val added = addToCourseModal.openWithClosePromise().await() != null

        if (added) {
            createAndBuild().await()
        }
        return EzCollComp.ResultUnmodified
    }

    private suspend fun dirSettings(item: EzCollComp.Item<Props>): EzCollComp.Result {
        updateDirModal.dirId = item.props.dirId
        updateDirModal.dirCurrentName = item.props.title
        updateDirModal.createAndBuild().await()
        val newTitle = updateDirModal.openWithClosePromise().await()
        return if (newTitle != null) {
            item.props.title = newTitle
            item.title = newTitle
            EzCollComp.ResultModified(listOf(item))
        } else
            EzCollComp.ResultUnmodified
    }

    private suspend fun permissions(item: EzCollComp.Item<Props>): EzCollComp.Result {
        itemPermissionsModal.dirId = item.props.dirId
        itemPermissionsModal.isDir = item.props is DirProps
        itemPermissionsModal.setTitle(item.props.title)
        val permissionsChanged = itemPermissionsModal.refreshAndOpen().await()
        debug { "Permissions changed: $permissionsChanged" }
        if (permissionsChanged)
            createAndBuild().await()
        return EzCollComp.ResultUnmodified
    }

    private suspend fun deleteDir(item: EzCollComp.Item<Props>): EzCollComp.Result {
        confirmDeleteDirModal.setText(StringComp.boldTriple(Str.deleteDir + " ", item.props.title, "?"))
        confirmDeleteDirModal.primaryAction = {
            try {
                LibraryDirDAO.deleteDir(item.props.dirId).await()
                true
            } catch (e: HandledResponseError) {
                false
            }
        }
        return if (confirmDeleteDirModal.openWithClosePromise().await()) {
            successMessage { Str.deleted }
            EzCollComp.ResultModified<Props>(emptyList())
        } else {
            EzCollComp.ResultUnmodified
        }
    }

    private suspend fun deleteExercise(item: EzCollComp.Item<Props>): EzCollComp.Result {
        confirmDeleteExerciseModal.setText(StringComp.boldTriple(Str.deleteExercise + " ", item.props.title, "?"))
        confirmDeleteExerciseModal.primaryAction = {
            try {
                item.props as ExerciseProps
                ExerciseDAO.deleteExercise(item.props.exerciseId).await()
                true
            } catch (e: HandledResponseError) {
                false
            }
        }
        return if (confirmDeleteExerciseModal.openWithClosePromise().await()) {
            successMessage { Str.deleted }
            EzCollComp.ResultModified<Props>(emptyList())
        } else {
            EzCollComp.ResultUnmodified
        }
    }
}
