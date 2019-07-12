package core.aas


class NoExecutorsException(override val message: String) : RuntimeException(message)

class ExecutorOverloadException(override val message: String) : RuntimeException(message)

class ExecutorException(override val message: String) : RuntimeException(message)
