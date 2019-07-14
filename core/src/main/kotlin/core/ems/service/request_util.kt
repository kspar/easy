package core.ems.service

import core.exception.InvalidRequestException
import core.exception.ReqError


fun String.idToLongOrInvalidReq(): Long =
        this.toLongOrNull()
                ?: throw InvalidRequestException("No entity with id $this",
                        ReqError.ENTITY_WITH_ID_NOT_FOUND, "id" to this)
