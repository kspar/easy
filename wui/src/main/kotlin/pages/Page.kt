package pages

import PageId

interface Page {

    val pageId: PageId

    fun pathMatches(path: String): Boolean

    fun build()
}