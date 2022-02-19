package pages.sidenav

import Role
import Str
import components.form.SelectComp
import debug
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import tmRender


class SidenavHeadAccountSection(
    private val userName: String,
    private val userEmail: String,
    private var activeRole: Role,
    private val availableRoles: List<Role>,
    private val onRoleChanged: (newRole: Role) -> Unit,
    parent: Component
) : Component(parent) {

    private var roleSelectComp: SelectComp? = null

    override val children
        get() = listOfNotNull(roleSelectComp)

    override fun create() = doInPromise {
        if (availableRoles.size > 1) {
            val roles = availableRoles.map {
                SelectComp.Option(Str.translateRole(it), it.id, it == activeRole)
            }
            roleSelectComp = SelectComp(null, roles, onOptionChange = ::roleChanged, parent = this)
        }
    }

    override fun render(): String = tmRender(
        "t-c-sidenav-head-account-section",
        "userName" to userName,
        "userEmail" to userEmail,
        "canSwitchRole" to (availableRoles.size > 1),
        "userRole" to Str.translateRole(activeRole),
        "rolesAvailable" to availableRoles.map {
            mapOf("id" to it.id, "name" to Str.translateRole(it), "isSelected" to (it == activeRole))
        },
        "roleSelectId" to roleSelectComp?.dstId,
    )

    private fun roleChanged(newRoleId: String?) {
        debug { "Change role to $newRoleId" }
        val newRole = availableRoles.first { it.id == newRoleId }
        activeRole = newRole
        onRoleChanged(newRole)
    }
}