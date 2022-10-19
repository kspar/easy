package core.ems.service.access_control

import core.conf.security.EasyUser
import core.exception.AccessControlException
import core.exception.ForbiddenException
import core.exception.ReqError


fun EasyUser.assertAccess(f: AccessChecksBuilder.() -> Unit) {
    val builder = AccessChecksBuilder(this)
    f(builder)
    builder.checkAll()
}


fun interface AccessCheck {
    fun validate(caller: EasyUser)
}

class AccessChecksBuilder(private val caller: EasyUser) {

    private val accessChecks: MutableList<AccessCheck> = mutableListOf()

    fun add(check: AccessCheck): AccessCheck {
        accessChecks.add(check)
        return check
    }

    infix fun AccessCheck.or(that: AccessCheck): AccessCheck {
        // Assume 2 checks that are combined were already added
        val removed1 = accessChecks.removeLast()
        val removed2 = accessChecks.removeLast()
        if (removed1 != that || removed2 != this) {
            throw IllegalStateException("Access checks don't match: $removed1 != $that or $removed2 != $this")
        }

        return add {
            try {
                this.validate(it)
            } catch (e: AccessControlException) {
                that.validate(it)
            }
        }
    }

    fun checkAll() {
        accessChecks.forEach { it.validate(caller) }
    }


    @Deprecated("For debugging only", ReplaceWith(""))
    fun testTrue() = add {}

    @Deprecated("For debugging only", ReplaceWith(""))
    fun testFalse() = add { throw ForbiddenException("", ReqError.ENTITY_WITH_ID_NOT_FOUND) }
}
