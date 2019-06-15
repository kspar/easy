package pages

import PageId
import PageState

/**
 * Represents a page with a unique path scheme and rendering logic.
 * Implementations can utilize global page state in [PageState].
 */
interface Page {

    /**
     * Unique page id
     */
    val pageId: PageId

    /**
     * Determine whether the given path should be served by this page.
     */
    fun pathMatches(path: String): Boolean

    /**
     * Build the current page: fetch resources, perform requests, render templates, add listeners etc.
     */
    fun build()
}