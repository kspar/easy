package pages.exercise_library.permissions_modal

import Auth
import Icons
import Role
import components.form.SelectComp
import components.form.StringFieldComp
import components.modal.ModalComp
import dao.LibraryDirDAO
import debug
import errorMessage
import kotlinx.coroutines.await
import pages.exercise_library.DirAccess
import pages.exercise_library.ExerciseLibraryPage
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.plainDstStr
import template
import translation.Str
import kotlin.js.Promise

class PermissionsModalComp(
    var dirId: String? = null,
    var isDir: Boolean = false,
    private val currentDirId: String? = null,
    private val title: String = "",
    parent: Component,
) : Component(parent) {

    private lateinit var modalComp: ModalComp<Boolean>
    private lateinit var permissionsList: PermissionsListLoaderComp

    private var permissionsChanged = false

    override val children: List<Component>
        get() = listOf(modalComp)

    override fun create() = doInPromise {
        modalComp = ModalComp(
            "Jagamine", onOpen = { }, fixFooter = true,
            defaultReturnValue = false,
            bodyCompsProvider = {
                val list = PermissionsListLoaderComp(
                    dirId, isDir, currentDirId, { modalComp.setLoading(it) }, { permissionsChanged = true }, it
                )
                permissionsList = list
                listOf(list)
            },
            parent = this
        )
    }

    override fun postChildrenBuilt() {
        modalComp.setTitle(title)
    }

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
    private val onLoadingChange: (isLoading: Boolean) -> Unit,
    private val onPermissionsChanged: () -> Unit,
    parent: Component,
) : Component(parent) {

    private var permissionsList: PermissionsListComp? = null

    override val children: List<Component>
        get() = listOfNotNull(permissionsList)

    override fun create() = doInPromise {
        dirId?.let {
            permissionsList = PermissionsListComp(it, isDir, currentDirId, onLoadingChange, onPermissionsChanged, this)
        }
    }

    override fun render() = plainDstStr(permissionsList?.dstId)
}


class PermissionsListComp(
    private val dirId: String,
    private val isDir: Boolean,
    private val currentDirId: String?,
    private val onLoadingChange: (isLoading: Boolean) -> Unit,
    private val onPermissionsChanged: () -> Unit,
    parent: Component,
) : Component(parent) {

    private lateinit var newAccessInput: StringFieldComp

    data class DirectAccess(
        val subjectName: String, val isGroup: Boolean, val email: String?, val level: DirAccess, val select: SelectComp
    )

    data class InheritingDir(val name: String, val id: String, val accesses: List<InheritedAccess>)
    data class InheritedAccess(val subjectName: String, val isGroup: Boolean, val email: String?, val level: DirAccess)

    private lateinit var directAccesses: List<DirectAccess>
    private lateinit var inheritedAccesses: List<InheritingDir>
    private lateinit var existingSubjects: Set<String>

    override val children: List<Component>
        get() = directAccesses.map { it.select } + newAccessInput

    override fun create() = doInPromise {
        onLoadingChange(true)
        val accesses = LibraryDirDAO.getDirAccesses(dirId).await()

        existingSubjects = accesses.direct_accounts.mapNotNull { it.email }.toSet() +
                accesses.direct_groups.map { it.id }

        newAccessInput = StringFieldComp(
            "", false, "Jagamiseks sisesta kasutaja email",
            // TODO: should have a button or some other way to activate this on mobile
            onENTER = ::onNewAccess,
            parent = this
        )

        directAccesses = buildList {
            // Accounts
            accesses.direct_accounts.sortedWith(
                compareByDescending<LibraryDirDAO.AccountAccess> { it.username == Auth.username }
                    .thenByDescending { it.access }
                    .thenBy { it.family_name }
                    .thenBy { it.given_name }
            ).map {
                val select = createSelectPermission(
                    it.access, PermissionSubjectGroup(it.group_id), it.username != Auth.username
                )
                add(DirectAccess("${it.given_name} ${it.family_name}", false, it.email, it.access, select))
            }

            // Any
            accesses.direct_any?.let {
                val select = createSelectPermission(it.access, PermissionSubjectAny, Auth.activeRole == Role.ADMIN)
                add(DirectAccess("Kõik kasutajad", true, null, it.access, select))
            }

            // Groups
            accesses.direct_groups.sortedWith(
                compareByDescending<LibraryDirDAO.GroupAccess> { it.access }
                    .thenBy { it.name }
            ).map {
                val select = createSelectPermission(it.access, PermissionSubjectGroup(it.id), true)
                add(DirectAccess(it.name, true, null, it.access, select))
            }
        }

        val dirs = mutableMapOf<LibraryDirDAO.InheritingDir, MutableList<InheritedAccess>>()

        // Accounts
        accesses.inherited_accounts.sortedWith(
            compareByDescending<LibraryDirDAO.AccountAccess> { it.username == Auth.username }
                .thenByDescending { it.access }
                .thenBy { it.family_name }
                .thenBy { it.given_name }
        ).forEach {
            it.inherited_from!!
            dirs.getOrPut(it.inherited_from) { mutableListOf() }
                .add(InheritedAccess("${it.given_name} ${it.family_name}", false, it.email, it.access))
        }

        // Any
        accesses.inherited_any?.let {
            it.inherited_from!!
            dirs.getOrPut(it.inherited_from) { mutableListOf() }
                .add(InheritedAccess("Kõik kasutajad", true, null, it.access))
        }

        // Groups
        accesses.inherited_groups.sortedWith(
            compareByDescending<LibraryDirDAO.GroupAccess> { it.access }
                .thenBy { it.name }
        ).forEach {
            it.inherited_from!!
            dirs.getOrPut(it.inherited_from) { mutableListOf() }
                .add(InheritedAccess(it.name, true, null, it.access))
        }

        inheritedAccesses = dirs.map {
            InheritingDir(it.key.name, it.key.id, it.value)
        }.sortedByDescending { it.id.toInt() }  // hack to order by parents' closeness
    }

    override fun render() = template(
        """
            <ez-exercise-permissions>
                <ez-add-permission id="{{newAccessDst}}"></ez-add-permission>
                {{#directPermissions}}
                    <ez-permission>
                        <ez-permission-subject>
                            <ez-permission-name>{{#isGroup}}{{{groupIcon}}}{{/isGroup}}{{name}}</ez-permission-name>
                            <ez-permission-email>{{email}}</ez-permission-email>
                        </ez-permission-subject>
                        <ez-dst id="{{selectDst}}"></ez-dst>
                    </ez-permission>
                {{/directPermissions}}
        
                {{#inheritingDirs}}
                    <ez-inheriting-dir>{{dirLabel}} <a {{^isCurrent}}href="{{dirHref}}"{{/isCurrent}}>{{dirName}}</a> {{#isCurrent}}{{currentDirLabel}}{{/isCurrent}}</ez-inheriting-dir>
                    {{#permissions}}
                        <ez-permission class="inherited">
                            <ez-permission-subject>
                                <ez-permission-name>{{#isGroup}}{{{groupIcon}}}{{/isGroup}}{{name}}</ez-permission-name>
                                <ez-permission-email>{{email}}</ez-permission-email>
                            </ez-permission-subject>
                            <ez-permission-access>{{access}}</ez-permission-access>
                        </ez-permission>
                    {{/permissions}}
                {{/inheritingDirs}}
            </ez-exercise-permissions>
        """.trimIndent(),
        "newAccessDst" to newAccessInput.dstId,
        "groupIcon" to Icons.groups,
        "dirLabel" to "Päritud kaustalt",
        "currentDirLabel" to "(see kaust)",
        "directPermissions" to directAccesses.map {
            mapOf(
                "isGroup" to it.isGroup,
                "name" to it.subjectName,
                "email" to it.email,
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
                        "name" to it.subjectName,
                        "email" to it.email,
                        "access" to Str.translatePermission(it.level),
                    )
                }
            )
        }
    )

    override fun postChildrenBuilt() {
        newAccessInput.focus()
        onLoadingChange(false)
    }

    sealed interface PermissionSubject
    data class PermissionSubjectGroup(val groupId: String) : PermissionSubject
    data class PermissionSubjectNewAcc(val newAccountEmail: String) : PermissionSubject
    object PermissionSubjectAny : PermissionSubject

    private fun createSelectPermission(access: DirAccess, subject: PermissionSubject, isEditable: Boolean) =
        SelectComp(
            options = buildList {
                if (access == DirAccess.P)
                    add(SelectComp.Option(Str.translatePermission(DirAccess.P), "P", true))
                add(SelectComp.Option(Str.translatePermission(DirAccess.PR), "PR", access == DirAccess.PR))
                if (isDir)
                    add(SelectComp.Option(Str.translatePermission(DirAccess.PRA), "PRA", access == DirAccess.PRA))
                add(SelectComp.Option(Str.translatePermission(DirAccess.PRAW), "PRAW", access == DirAccess.PRAW))
                add(SelectComp.Option(Str.translatePermission(DirAccess.PRAWM), "PRAWM", access == DirAccess.PRAWM))
                if (access != DirAccess.P)
                    add(SelectComp.Option("Eemalda juurdepääs", ""))
            },
            onOptionChange = {
                val selectedAccess = when (it) {
                    "" -> null
                    null -> null
                    "P" -> DirAccess.P
                    "PR" -> DirAccess.PR
                    "PRA" -> DirAccess.PRA
                    "PRAW" -> DirAccess.PRAW
                    "PRAWM" -> DirAccess.PRAWM
                    else -> error("Unmapped permission string: $it")
                }
                putPermission(selectedAccess, subject)
            },
            isDisabled = !isEditable,
            unconstrainedPosition = true,
            parent = this
        )

    private suspend fun onNewAccess(subjectStr: String) {
        val subject = when {
            subjectStr.lowercase() == "kõik kasutajad" -> {
                if (Auth.activeRole == Role.ADMIN)
                    PermissionSubjectAny
                else {
                    errorMessage {
                        "Sul pole õigust kõikidele kasutajatele jagamiseks, pöördu selle sooviga administraatori poole."
                    }
                    return
                }
            }

            subjectStr.contains("@") -> PermissionSubjectNewAcc(subjectStr)
            else -> PermissionSubjectGroup(subjectStr)
        }
        putPermission(DirAccess.PR, subject)
    }

    private suspend fun putPermission(access: DirAccess?, subject: PermissionSubject) {
        debug { "Put permission for $subject to $access" }
        onLoadingChange(true)

        val s = when (subject) {
            is PermissionSubjectGroup -> LibraryDirDAO.Group(subject.groupId)
            is PermissionSubjectNewAcc -> LibraryDirDAO.NewAccount(subject.newAccountEmail)
            is PermissionSubjectAny -> LibraryDirDAO.Any
        }

        // if an existing subject is added again, do nothing
        if (subject !is PermissionSubjectNewAcc || !existingSubjects.contains(subject.newAccountEmail)) {
            // TODO: try-catch no account / no group
            LibraryDirDAO.putDirAccess(dirId, s, access).await()
            onPermissionsChanged()
        }

        createAndBuild().await()
    }
}