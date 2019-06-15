package pages

import PageId
import PageState
import getElemById
import kotlin.dom.clear

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
     * Clear page, this is typically called before [build].
     * Implementations should override to perform custom clearing operations.
     * The default implementations clears the whole container.
     */
    fun clear() {
        getElemById("container").clear()
    }

    /**
     * Build the current page: fetch resources, perform requests, render templates, add listeners etc.
     */
    fun build()
}