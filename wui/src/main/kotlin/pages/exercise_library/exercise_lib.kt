package pages.exercise_library

import EzDate
import Icons
import components.BreadcrumbsComp
import components.Crumb
import components.EzCollComp
import dao.ExerciseDAO
import dao.LibraryDAO
import dao.LibraryDirDAO
import debug
import kotlinx.coroutines.await
import pages.exercise_in_library.AddToCourseModalComp
import pages.exercise_in_library.ExercisePage
import pages.exercise_library.permissions_modal.PermissionsModalComp
import pages.sidenav.Sidenav
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
        open val title: String,
        open val access: DirAccess,
        open val isShared: Boolean,
        val type: Int,
    )

    data class ExerciseProps(
        val exerciseId: String,
        override val dirId: String,
        override val title: String,
        override val access: DirAccess,
        override val isShared: Boolean,
        val graderType: ExerciseDAO.GraderType,
        val coursesCount: Int,
        val modifiedAt: EzDate,
        val modifiedBy: String,
    ) : Props(dirId, title, access, isShared, 1)

    data class DirProps(
        override val dirId: String,
        override val title: String,
        override val access: DirAccess,
        override val isShared: Boolean,
    ) : Props(dirId, title, access, isShared, 0)

    private lateinit var breadcrumbs: BreadcrumbsComp
    private lateinit var ezcoll: EzCollComp<Props>
    private val addToCourseModal = AddToCourseModalComp(emptyList(), "", this)
    private lateinit var currentDirPermissionsModal: PermissionsModalComp
    private val itemPermissionsModal = PermissionsModalComp(currentDirId = dirId, parent = this)
    private val newExerciseModal = CreateExerciseModalComp(dirId, null, this)
    private val newDirModal = CreateDirModalComp(dirId, this)

    override val children: List<Component>
        get() = listOf(
            breadcrumbs, ezcoll, addToCourseModal, currentDirPermissionsModal, itemPermissionsModal,
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
                                successMessage { "Ülesanne loodud" }
                            }
                        })
                        add(Sidenav.Action(Icons.newFolder, "Uus kaust") {
                            if (newDirModal.openWithClosePromise().await() != null)
                                createAndBuild().await()
                        })

                        if (currentDirAccess == DirAccess.PRAWM) {
                            add(Sidenav.Action(Icons.addPerson, "Jagamine") {
                                // Set dirId here to avoid loading permissions if user has no M access
                                currentDirPermissionsModal.dirId = dirId
                                val permissionsChanged = currentDirPermissionsModal.refreshAndOpen().await()
                                debug { "Permissions changed: $permissionsChanged" }
                                if (permissionsChanged) {
                                    successMessage { "Õigused muudetud" }
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
                titleIcon = if (p.isShared) EzCollComp.TitleIcon(Icons.teacher, "Jagatud") else null,
                titleInteraction = EzCollComp.TitleLink(ExercisePage.link(p.exerciseId)),
                topAttr = EzCollComp.SimpleAttr(
                    "Viimati muudetud",
                    "${p.modifiedAt.toHumanString(EzDate.Format.DATE)} · ${p.modifiedBy}",
                    longValue = "${p.modifiedAt.toHumanString(EzDate.Format.FULL)} · ${p.modifiedBy}"
                ),
                bottomAttrs = listOf(
                    EzCollComp.SimpleAttr("Kasutusel", "${p.coursesCount} kursusel", Icons.courses),
//                    EzCollComp.SimpleAttr("ID", p.id, Icons.id),
//                    EzCollComp.SimpleAttr(
//                        "Mul on lubatud",
//                        p.access.toString(),
//                        Icons.exercisePermissions,
//                        translateDirAccess(p.access)
//                    ),
                ),
                isSelectable = true,
                actions = buildList {
                    add(EzCollComp.Action(Icons.add, "Lisa kursusele", onActivate = ::addToCourse))
                    if (p.access == DirAccess.PRAWM)
                        add(EzCollComp.Action(Icons.addPerson, "Jagamine", onActivate = ::permissions))
                },
            )
        } + dirProps.map { p ->
            EzCollComp.Item<Props>(
                p,
                EzCollComp.ItemTypeIcon(if (p.isShared) Icons.sharedFolder else Icons.library),
                p.title,
                titleInteraction = EzCollComp.TitleLink(ExerciseLibraryPage.linkToDir(p.dirId)),
//                bottomAttrs = listOf(
//                    EzCollComp.SimpleAttr("ID", p.id, Icons.id),
//                    EzCollComp.SimpleAttr(
//                        "Mul on lubatud",
//                        p.access.toString(),
//                        Icons.exercisePermissions,
//                        translateDirAccess(p.access)
//                    ),
//                ),
                isSelectable = false,
                actions = buildList {
                    if (p.access == DirAccess.PRAWM)
                        add(EzCollComp.Action(Icons.addPerson, "Jagamine", onActivate = ::permissions))
//                    EzCollComp.Action(Icons.delete, "Kustuta", onActivate = {}),
                },
            )
        }

        ezcoll = EzCollComp<Props>(
            items, EzCollComp.Strings("asi", "asja"),
            massActions = listOf(
                EzCollComp.MassAction<Props>(Icons.add, "Lisa kursusele", ::addToCourse)
            ),
            filterGroups = listOf(
                EzCollComp.FilterGroup<Props>(
                    "Jagamine", listOf(
                        EzCollComp.Filter("Jagatud") { it.props.isShared },
                        EzCollComp.Filter("Privaatsed") { !it.props.isShared },
                    )
                ),
                EzCollComp.FilterGroup<Props>(
                    "Hindamine", listOf(
                        EzCollComp.Filter("Automaatkontrolliga") {
                            it.props is ExerciseProps && it.props.graderType == ExerciseDAO.GraderType.AUTO
                        },
                        EzCollComp.Filter("Käsitsi hinnatavad") {
                            it.props is ExerciseProps && it.props.graderType == ExerciseDAO.GraderType.TEACHER
                        },
                    )
                ),
            ),
            sorters = listOf(
                EzCollComp.Sorter<Props>("Nime järgi",
                    compareBy<EzCollComp.Item<Props>> {
                        if (it.props is ExerciseProps) 1 else 0
                    }.thenBy {
                        it.props.title
                    }
                ),
                EzCollComp.Sorter("Muutmisaja järgi",
                    compareByDescending<EzCollComp.Item<Props>> {
                        if (it.props is ExerciseProps) it.props.modifiedAt else EzDate.future()
                    }.thenBy {
                        it.props.title
                    }
                ),
                EzCollComp.Sorter<Props>("Populaarsuse järgi",
                    compareByDescending<EzCollComp.Item<Props>> {
                        if (it.props is ExerciseProps) it.props.coursesCount else Int.MAX_VALUE
                    }.thenBy {
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
}
