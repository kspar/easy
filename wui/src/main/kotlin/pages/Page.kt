package pages

import AppState
import PageId
import getElemById
import kotlin.browser.window
import kotlin.dom.clear

/**
 * Represents a page with a unique path scheme and rendering logic.
 * Implementations can utilize global app state in [AppState].
 *
 * This class abstracts storing and retrieving page state using the browser's History API:
 * store state via [updateState] and retrieve state via [build]'s parameter.
 * Therefore, implementations should not use the History API (window.history) directly
 * to avoid circumventing the type safety of page state objects.
 *
 * @param T Type of the page's state object. Pass [Nothing] if storing and retrieving state is not desired.
 */
abstract class Page<T> {

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
     */
    abstract fun build(pageState: T?)

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
    fun updateState(pageState: T) {
        window.history.replaceState(pageState, "")
    }
}
