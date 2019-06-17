package pages

import PageId
import AppState
import getElemById
import kotlin.browser.window
import kotlin.dom.clear

/**
 * Represents a page with a unique path scheme and rendering logic.
 * Implementations can utilize global page state in [AppState].
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
     * If no state object is available then null is passed.
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
     * Update page state in history. The given state object can be defined by the implementation
     * and will be later passed to [build] if it's available.
     * This state persists over browser navigation but not refresh.
     */
    fun updateState(pageState: Any) {
        window.history.replaceState(pageState, "")
    }
}
