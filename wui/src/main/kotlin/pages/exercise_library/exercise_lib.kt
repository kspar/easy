package pages.exercise_library

import CompDate
import Icons
import Str
import components.BreadcrumbsComp
import components.Crumb
import components.EzCollComp
import dao.LibraryDAO
import dao.LibraryDirDAO
import debug
import kotlinx.coroutines.await
import pages.exercise.AddToCourseModalComp
import pages.exercise.ExercisePage
import pages.exercise.GraderType
import pages.sidenav.Sidenav
import plainDstStr
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.EzSpa
import rip.kspar.ezspa.doInPromise
import successMessage
import toComparable
import toEstonianString


class ExerciseLibRootComp(
    private val dirId: String?,
    private val setPathSuffix: (String) -> Unit,
    dstId: String
) : Component(null, dstId) {

    abstract class Props(
        open val id: String,
        open val title: String,
        open val access: DirAccess,
        val type: Int,
    )

    data class ExerciseProps(
        override val id: String,
        override val title: String,
        override val access: DirAccess,
        val graderType: GraderType,
        val coursesCount: Int,
        val modifiedAt: CompDate,
    ) : Props(id, title, access, 1)

    data class DirProps(
        override val id: String,
        override val title: String,
        override val access: DirAccess,
    ) : Props(id, title, access, 0)

    private lateinit var breadcrumbs: BreadcrumbsComp
    private lateinit var ezcoll: EzCollComp<Props>
    private val newExerciseModal = CreateExerciseModalComp(dirId, null, this)
    private val addToCourseModal = AddToCourseModalComp(emptyList(), "", this)
    private val newDirModal = CreateDirModalComp(dirId, this)

    override val children: List<Component>
        get() = listOf(breadcrumbs, ezcoll, newExerciseModal, addToCourseModal, newDirModal)

    override fun create() = doInPromise {

        val libRespPromise = LibraryDAO.getLibraryContent(dirId)

        breadcrumbs = if (dirId != null) {
            val parents = LibraryDirDAO.getDirParents(dirId).await().reversed()
            // Current dir must exist in response if dirId != null
            val currentDir = libRespPromise.await().current_dir!!

            setPathSuffix(createPathChainSuffix(parents.map { it.name } + currentDir.name))

            BreadcrumbsComp(createDirChainCrumbs(parents, currentDir.name), this)

        } else {
            BreadcrumbsComp(listOf(Crumb(Str.exerciseLibrary())), this)
        }

        val libResp = libRespPromise.await()

        // can create new exercise only if current dir is root, or we have at least PRA
        val currentDirAccess = libResp.current_dir?.effective_access
        if (currentDirAccess == null || currentDirAccess >= DirAccess.PRA) {
            Sidenav.replacePageSection(
                Sidenav.PageSection(
                    Str.exerciseLibrary(), listOf(
                        Sidenav.Action(Icons.newExercise, "Uus ülesanne") {
                            val exerciseId = newExerciseModal.openWithClosePromise().await()
                            if (exerciseId != null) {
                                EzSpa.PageManager.navigateTo(ExercisePage.link(exerciseId))
                                successMessage { "Ülesanne loodud" }
                            }
                        },
                        Sidenav.Action(Icons.newFolder, "Uus kaust") {
                            if (newDirModal.openWithClosePromise().await() != null)
                                createAndBuild().await()
                        },
                    )
                )
            )
        }

        val exerciseProps = libResp.child_exercises.map {
            ExerciseProps(
                it.exercise_id,
                it.title,
                it.effective_access,
                it.grader_type,
                it.courses_count,
                it.modified_at.toComparable(),
            )
        }

        val dirProps = libResp.child_dirs.map {
            DirProps(it.id, it.name, it.effective_access)
        }

        val items: List<EzCollComp.Item<Props>> = exerciseProps.map { p ->
            EzCollComp.Item<Props>(
                p,
                if (p.graderType == GraderType.AUTO)
                    EzCollComp.ItemTypeIcon(Icons.robot)
                else
                    EzCollComp.ItemTypeIcon(Icons.user),
                p.title,
                titleLink = ExercisePage.link(p.id),
                topAttr = EzCollComp.SimpleAttr(
                    "Viimati muudetud",
                    p.modifiedAt.date.toEstonianString(),
                    Icons.pending
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
                    // TODO: is shared -> attr "shared" with Icons.teacher
                ),
                isSelectable = true,
                actions = listOf(
                    EzCollComp.Action(Icons.add, "Lisa kursusele...", onActivate = ::addToCourse)
                ),
            )
        } + dirProps.map { p ->
            EzCollComp.Item<Props>(
                p,
                // TODO: is shared -> Icons.sharedFolder
                EzCollComp.ItemTypeIcon(Icons.library),
                p.title,
                titleLink = ExerciseLibraryPage.linkToDir(p.id),
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
                actions = listOf(
                    EzCollComp.Action(Icons.delete, "Kustuta", onActivate = {
                        // TODO
                        debug { "delete" }
                        EzCollComp.ResultUnmodified
                    })
                ),
            )
        }

        ezcoll = EzCollComp<Props>(
            items, EzCollComp.Strings("asi", "asja"),
            massActions = listOf(
                EzCollComp.MassAction<Props>(Icons.add, "Lisa kursusele...", ::addToCourse)
            ),
            filterGroups = listOf(
                EzCollComp.FilterGroup<Props>(
                    "Tüüp", listOf(
                        EzCollComp.Filter("Kaustad") {
                            it.props is DirProps
                        },
                        EzCollComp.Filter("Ülesanded") {
                            it.props is ExerciseProps
                        },
                    )
                ),
                EzCollComp.FilterGroup<Props>(
                    "Hindamine", listOf(
                        EzCollComp.Filter("Automaatkontrolliga") {
                            it.props is ExerciseProps && it.props.graderType == GraderType.AUTO
                        },
                        EzCollComp.Filter("Käsitsi hinnatavad") {
                            it.props is ExerciseProps && it.props.graderType == GraderType.TEACHER
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
                        if (it.props is ExerciseProps) it.props.modifiedAt else CompDate.future()
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

    override fun render() =
        plainDstStr(breadcrumbs.dstId, ezcoll.dstId, newExerciseModal.dstId, addToCourseModal.dstId, newDirModal.dstId)

    private suspend fun addToCourse(item: EzCollComp.Item<Props>): EzCollComp.Result {
        addToCourseModal.setSingleExercise(item.props.id, item.props.title)
        return openAddToCourse()
    }

    private suspend fun addToCourse(items: List<EzCollComp.Item<Props>>): EzCollComp.Result {
        if (items.size == 1) {
            val item = items.single()
            addToCourseModal.setSingleExercise(item.props.id, item.props.title)
        } else {
            addToCourseModal.setMultipleExercises(items.map { it.props.id })
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
}
