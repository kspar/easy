package rip.kspar.ezspa

object IdGenerator {
    private const val PREFIX = "ezid-"
    private var counter: Long = 0

    fun nextId(): String = PREFIX + counter++

    fun nextLongId(): Long = counter++
}
