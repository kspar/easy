package spa

import getElemById
import kotlin.browser.window
import kotlin.dom.clear

/**
 * Represents a page with a unique path scheme and rendering logic.
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
     * Human-readable page name used for representing pages in logs, should usually
     * be unique among all the pages. Typically using an enum is a good idea to guarantee
     * uniqueness. However, a simple String can also be used. [Any.toString] will be called
     * to generate a string representation.
     */
    abstract val pageName: Any

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
