package pages

interface Page {

    fun pathMatches(path: String): Boolean

    fun build()
}