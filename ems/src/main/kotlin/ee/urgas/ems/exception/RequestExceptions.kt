package ee.urgas.ems.exception

class InvalidRequestException(override val message: String) : RuntimeException(message)

class ForbiddenException(override val message: String) : RuntimeException(message)
