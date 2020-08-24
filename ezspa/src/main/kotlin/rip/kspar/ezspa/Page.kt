package rip.kspar.ezspa

import kotlinx.browser.window

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
     * Clear page. Called before [build].
     * The default implementation performs no clearing.
     */
    open fun clear() {}

    /**
     * Check if navigation to this page is allowed. Can check for example user's role and any other internal state.
     * Should throw an exception if the checks fail - no navigation is performed in that case.
     * Called before [clear].
     * The default implementation does not perform any checks.
     */
    open fun assertAuthorisation() {}

    /**
     * Destruct built page. Called when navigating away from this page.
     * The default implementation does nothing.
     */
    open fun destruct() {}

    /**
     * Called *before* navigating away from this page to an internal destination using regular navigation i.e.
     * links or [PageManager.navigateTo]. Not called when navigating to external sites or using browser history navigation.
     * Can be useful for caching pages (see [updateState]).
     * The default implementation does nothing.
     */
    open fun onPreNavigation() {}

    /**
     * Update page state in history. The given state string will be later passed to [build] if it's available.
     * This state persists over browser navigation but not refresh.
     */
    fun updateState(pageStateStr: String) {
        window.history.replaceState(pageStateStr, "")
    }

    /**
     * Update current page URL. Takes a URL fragment that can be relative ('foo') or absolute ('/foo') or
     * only query string ('?foo') or only hash string ('#foo') or a combination of these.
     */
    fun updateUrl(urlFragment: String) {
        window.history.replaceState(null, "", urlFragment)
    }
}
