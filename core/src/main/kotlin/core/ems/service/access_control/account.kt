package core.ems.service.access_control

import core.conf.security.EasyUser
import core.exception.ForbiddenException
import core.exception.ReqError

fun AccessChecksBuilder.admin() = add { caller: EasyUser ->
    if (!caller.isAdmin())
        throw ForbiddenException("Admin role required", ReqError.ROLE_NOT_ALLOWED)
}
