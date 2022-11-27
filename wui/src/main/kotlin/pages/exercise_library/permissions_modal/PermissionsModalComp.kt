package pages.exercise_library.permissions_modal

import Auth
import Icons
import Str
import components.form.SelectComp
import components.modal.Modal
import components.modal.ModalComp
import dao.LibraryDirDAO
import debug
import kotlinx.coroutines.await
import pages.exercise_library.DirAccess
import pages.exercise_library.ExerciseLibraryPage
import plainDstStr
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import tmRender
import kotlin.js.Promise

class PermissionsModalComp(
    var dirId: String?,
    var isDir: Boolean,
    private val currentDirId: String?,
    parent: Component,
) : Component(parent) {

    private lateinit var modalComp: ModalComp<Boolean>
    private lateinit var permissionsList: PermissionsListLoaderComp

    private var permissionsChanged = false

    override val children: List<Component>
        get() = listOf(modalComp)

    override fun create() = doInPromise {
        modalComp = ModalComp(
            "Jagamine", onOpen = { },
            defaultReturnValue = false, id = Modal.DIR_PERMISSIONS,
            bodyCompsProvider = {
                val list = PermissionsListLoaderComp(dirId, isDir, currentDirId, { permissionsChanged = true }, it)
                permissionsList = list
                listOf(list)
            },
            parent = this
        )
    }

    override fun render() = plainDstStr(modalComp.dstId)

    fun setTitle(title: String) = modalComp.setTitle(title)

    fun refreshAndOpen(): Promise<Boolean> {
        val p = modalComp.openWithClosePromise()
        permissionsChanged = false
        permissionsList.dirId = dirId
        permissionsList.isDir = isDir
        permissionsList.createAndBuild()
        return p.then { permissionsChanged }
    }
}


class PermissionsListLoaderComp(
    var dirId: String?,
    var isDir: Boolean,
    private val currentDirId: String?,
    private val onPermissionsChanged: () -> Unit,
    parent: Component,
) : Component(parent) {

    private var permissionsList: PermissionsListComp? = null

    override val children: List<Component>
        get() = listOfNotNull(permissionsList)

    override fun create() = doInPromise {
        dirId?.let {
            permissionsList = PermissionsListComp(it, isDir, currentDirId, onPermissionsChanged, this)
        }
    }

    override fun render() = plainDstStr(permissionsList?.dstId)
}


class PermissionsListComp(
    private val dirId: String,
    private val isDir: Boolean,
    private val currentDirId: String?,
    private val onPermissionsChanged: () -> Unit,
    parent: Component,
) : Component(parent) {

    data class DirectAccess(val subjectName: String, val isGroup: Boolean, val level: DirAccess, val select: SelectComp)
    data class InheritingDir(val name: String, val id: String, val accesses: List<InheritedAccess>)
    data class InheritedAccess(val subjectName: String, val isGroup: Boolean, val level: DirAccess)

    private lateinit var directAccesses: List<DirectAccess>
    private lateinit var inheritedAccesses: List<InheritingDir>

    override val children: List<Component>
        get() = directAccesses.map { it.select }

    override fun create() = doInPromise {
        val accesses = LibraryDirDAO.getDirAccesses(dirId).await()

        // TODO: add

        directAccesses = buildList {
            // TODO: any
//            accesses.direct_any?.let {
//                add(UnorderedListComp.Item("Kõik: ${it.access.name}"))
//            }
            // TODO: test ordering
            // TODO: test group icon
            accesses.direct_accounts.sortedWith(
                compareByDescending<LibraryDirDAO.AccountAccess> { it.access }
                    .thenBy { it.family_name }
                    .thenBy { it.given_name }
            ).map {
                val select = createSelectPermission(it.access, it.username)
                add(DirectAccess("${it.given_name} ${it.family_name}", false, it.access, select))
            }
            accesses.direct_groups.sortedWith(
                compareByDescending<LibraryDirDAO.GroupAccess> { it.access }
                    .thenBy { it.name }
            ).map {
                val select = createSelectPermission(it.access, it.name)
                add(DirectAccess(it.name, true, it.access, select))
            }
        }

        val dirs = mutableMapOf<LibraryDirDAO.InheritingDir, MutableList<InheritedAccess>>()

        accesses.inherited_accounts.sortedWith(
            compareByDescending<LibraryDirDAO.AccountAccess> { it.access }
                .thenBy { it.family_name }
                .thenBy { it.given_name }
        ).forEach {
            it.inherited_from!!
            dirs.getOrPut(it.inherited_from) { mutableListOf() }
                .add(InheritedAccess("${it.given_name} ${it.family_name}", false, it.access))
        }

        accesses.inherited_groups.sortedWith(
            compareByDescending<LibraryDirDAO.GroupAccess> { it.access }
                .thenBy { it.name }
        ).forEach {
            it.inherited_from!!
            dirs.getOrPut(it.inherited_from) { mutableListOf() }
                .add(InheritedAccess(it.name, true, it.access))
        }

        inheritedAccesses = dirs.map {
            InheritingDir(it.key.name, it.key.id, it.value)
        }.sortedByDescending { it.id.toInt() }  // hack to order by parents' closeness
    }

    override fun render() = tmRender(
        "t-c-exercise-permissions",
        "groupIcon" to Icons.groups,
        "dirLabel" to "Päritud kaustalt",
        "currentDirLabel" to "(see kaust)",
        "directPermissions" to directAccesses.map {
            mapOf(
                "isGroup" to it.isGroup,
                "subject" to it.subjectName,
                "selectDst" to it.select.dstId,
            )
        },
        "inheritingDirs" to inheritedAccesses.map {
            mapOf(
                "dirName" to it.name,
                "isCurrent" to (it.id == currentDirId),
                "dirHref" to ExerciseLibraryPage.linkToDir(it.id),
                "permissions" to it.accesses.map {
                    mapOf(
                        "isGroup" to it.isGroup,
                        "subject" to it.subjectName,
                        "access" to Str.translatePermission(it.level),
                    )
                }
            )
        }
    )

    override fun renderLoading() = "Laen õiguseid..."

    private fun createSelectPermission(access: DirAccess, subject: String) =
        SelectComp(
            options = buildList {
                if (access == DirAccess.P)
                    add(SelectComp.Option(Str.translatePermission(DirAccess.P), "P", true))
                add(SelectComp.Option(Str.translatePermission(DirAccess.PR), "PR", access == DirAccess.PR))
                if (isDir)
                    add(SelectComp.Option(Str.translatePermission(DirAccess.PRA), "PRA", access == DirAccess.PRA))
                add(SelectComp.Option(Str.translatePermission(DirAccess.PRAW), "PRAW", access == DirAccess.PRAW))
                add(SelectComp.Option(Str.translatePermission(DirAccess.PRAWM), "PRAWM", access == DirAccess.PRAWM))
                add(SelectComp.Option("Eemalda juurdepääs", ""))
            },
            onOptionChange = { changePermission(it, subject) },
            isDisabled = access == DirAccess.P || subject == Auth.username,
            parent = this
        )

    private fun changePermission(access: String?, subject: String) {
        debug { "change permission for $subject to $access" }
        onPermissionsChanged()
    }
}