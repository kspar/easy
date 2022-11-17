package pages.exercise_library

import components.StringComp
import components.UnorderedListComp
import components.modal.Modal
import components.modal.ModalComp
import dao.LibraryDirDAO
import kotlinx.coroutines.await
import plainDstStr
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import kotlin.js.Promise

class PermissionsModalComp(
    var dirId: String? = null,
    parent: Component,
) : Component(parent) {


    private lateinit var modalComp: ModalComp<Boolean>
    private lateinit var permissionsList: PermissionsListLoaderComp

    override val children: List<Component>
        get() = listOf(modalComp)

    override fun create() = doInPromise {
        modalComp = ModalComp(
            "Jagamine", onOpen = { }, fixFooter = true,
            defaultReturnValue = false, id = Modal.DIR_PERMISSIONS,
            bodyCompsProvider = {
                val list = PermissionsListLoaderComp(dirId, it)
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
        permissionsList.dirId = dirId
        permissionsList.createAndBuild()
        return p
    }
}


class PermissionsListLoaderComp(
    var dirId: String?,
    parent: Component,
) : Component(parent) {

    private var permissionsList: PermissionsListComp? = null

    override val children: List<Component>
        get() = listOfNotNull(permissionsList)

    override fun create() = doInPromise {
        dirId?.let {
            permissionsList = PermissionsListComp(it, this)
        }
    }

    override fun render() = plainDstStr(permissionsList?.dstId)
}


class PermissionsListComp(
    var dirId: String,
    parent: Component,
) : Component(parent) {

    private var directLabel: StringComp? = null
    private lateinit var directList: UnorderedListComp
    private var inheritedLabel: StringComp? = null
    private lateinit var inheritedList: UnorderedListComp

    override val children: List<Component>
        get() = listOfNotNull(directLabel, directList, inheritedLabel, inheritedList)

    override fun create() = doInPromise {
        val accesses = LibraryDirDAO.getDirAccesses(dirId).await()

        directList = UnorderedListComp(
            buildList {
                accesses.direct_any?.let {
                    add(UnorderedListComp.Item("Kõik: ${it.access.name}"))
                }
                accesses.direct_accounts.map {
                    add(UnorderedListComp.Item("${it.username}: ${it.access.name}"))
                }
                accesses.direct_groups.map {
                    add(UnorderedListComp.Item("Grupp ${it.name}: ${it.access.name}"))
                }
            },
            parent = this
        )

        if (directList.allItems.isNotEmpty())
            directLabel = StringComp(StringComp.Part("Otsesed õigused:", StringComp.PartType.BOLD), this)

        inheritedList = UnorderedListComp(
            buildList {
                accesses.inherited_any?.let {
                    add(UnorderedListComp.Item("Kõik: ${it.access.name}"))
                }
                accesses.inherited_accounts.map {
                    add(UnorderedListComp.Item("${it.username}: ${it.access.name} (${it.inherited_from?.name})"))
                }
                accesses.inherited_groups.map {
                    add(UnorderedListComp.Item("Grupp ${it.name}: ${it.access.name} (${it.inherited_from?.name})"))
                }
            },
            parent = this
        )

        if (inheritedList.allItems.isNotEmpty())
            inheritedLabel = StringComp(StringComp.Part("Päritud õigused:", StringComp.PartType.BOLD), this)

    }

    override fun render() = plainDstStr(
        directLabel?.dstId, directList.dstId,
        inheritedLabel?.dstId, inheritedList.dstId,
    )

    override fun renderLoading() = "Laen õiguseid..."
}