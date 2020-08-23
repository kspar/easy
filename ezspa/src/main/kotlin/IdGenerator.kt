object IdGenerator {
    private const val PREFIX = "ezid-"
    private var counter = 0

    fun nextId(): String = PREFIX + counter++
}
