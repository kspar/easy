package core.ems.service

import core.exception.InvalidRequestException


fun String.idToLongOrInvalidReq(): Long =
        this.toLongOrNull()
                ?: throw InvalidRequestException("No entity with id")
