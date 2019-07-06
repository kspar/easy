package ee.urgas.ems.bl

import ee.urgas.ems.exception.InvalidRequestException


fun String.idToLongOrInvalidReq(): Long =
        this.toLongOrNull()
                ?: throw InvalidRequestException("No entity with id")
