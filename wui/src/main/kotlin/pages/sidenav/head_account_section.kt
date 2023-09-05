package pages.sidenav

import Role
import components.form.SelectComp
import debug
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import template
import translation.Str


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

    override fun render(): String = template(
        """
            <li>
                <div class="user-view">
                    <div class="background"></div>
                    <div class="name attr truncate">{{userName}}</div>
                    <div class="email attr truncate">{{userEmail}}</div>
                    {{#canSwitchRole}}
                        <div class="role-select-wrap">
                            <ez-dst id="{{roleSelectId}}"></ez-dst>
                        </div>
                    {{/canSwitchRole}}
                </div>
            </li>
        """.trimIndent(),
        "userName" to userName,
        "userEmail" to userEmail,
        "canSwitchRole" to (availableRoles.size > 1),
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