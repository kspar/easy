package spa

import getContainer
import kotlin.browser.window
import kotlin.dom.clear

/**
 * Represents a page with a unique path scheme and rendering logic.
 *
 * This class abstracts storing and retrieving page state using the browser's History API:
 * store serialized state objects via [updateState] and retrieve them via [build]'s parameter.
 */
abstract class Page {

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
     * A page state string is passed that was previously set by the page via [updateState].
     * If no state string is available then null is passed.
     */
    abstract fun build(pageStateStr: String?)

    /**
     * Clear page, this is typically called before [build].
     * The default implementation performs no clearing.
     */
    open fun clear() {}

    /**
     * Destruct built page. Called when navigating away from this page.
     */
    open fun destruct() {}

    /**
     * Update page state in history. The given state string will be later passed to [build] if it's available.
     * This state persists over browser navigation but not refresh.
     */
    fun updateState(pageStateStr: String) {
        window.history.replaceState(pageStateStr, "")
    }
}
