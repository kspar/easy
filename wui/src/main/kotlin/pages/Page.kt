package pages

import PageId
import PageState
import getElemById
import kotlin.browser.window
import kotlin.dom.clear

/**
 * Represents a page with a unique path scheme and rendering logic.
 * Implementations can utilize global page state in [PageState].
 */
abstract class Page {

    /**
     * Unique page id
     */
    abstract val pageId: PageId

    /**
     * Determine whether the given path should be served by this page.
     */
    abstract fun pathMatches(path: String): Boolean

    /**
     * Build the current page: fetch resources, perform requests, render templates, add listeners etc.
     * A page state object is passed that was previously set by the page via [updateState].
     * Note: No class info is preserved due to JS serialization, therefore is/as will not work.
     * Use [Any.unsafeCast] to restore the class.
     */
    abstract fun build(pageState: Any?)

    /**
     * Clear page, this is typically called before [build].
     * Implementations should override to perform custom clearing operations.
     * The default implementations clears the whole container.
     */
    open fun clear() {
        getElemById("container").clear()
    }

    /**
     * Update page state in history. The given state object will be
     * later passed to [build] if it's available.
     * This state persists over browser navigation but not refresh.
     */
    fun updateState(state: Any?) {
        window.history.replaceState(state, "")
    }
}