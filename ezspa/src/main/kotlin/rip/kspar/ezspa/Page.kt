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
     * be unique among all the pages. Typically, using an enum is a good idea to guarantee
     * uniqueness. However, a simple String can also be used. [Any.toString] will be called
     * to generate a string representation.
     */
    abstract val pageName: Any

    /**
     * String that defines which paths should be served by the page.
     *
     * Format: /some/stuff/{path-param-key}/bla
     *
     * Example: /courses/{courseId}/dashboard
     */
    abstract val pathSchema: String

    private val pathComponents: List<PathComponent> by lazy {
        pathSchema.split("/").filter { it.isNotBlank() }.map {
            val paramMatch = it.match("^{(\\w+)}$")
            if (paramMatch != null && paramMatch.size == 2) {
                val paramKey = paramMatch[1]
                PathParam(paramKey)
            } else {
                PathString(it)
            }
        }.also { EzSpa.Logger.debug { "Path schema $pathSchema parsed to $it" } }
    }

    private val pathRegex: String by lazy {
        pathComponents.joinToString(prefix = "^/", separator = "/", postfix = "/?$") {
            when (it) {
                is PathString -> it.str
                is PathParam -> "(\\w+)"
            }
        }
    }

    internal fun pathMatches(): Boolean = window.location.pathname.matches(pathRegex)

    /**
     * Return a map with path params extracted from the current path. The key for each path param value
     * comes from [pathSchema]. Example:
     *
     * pathSchema = "/ez/{par}/game"
     * currentPath = "/ez/w1337/game"
     * returns {par=w1337}
     */
    fun parsePathParams(): CertainMap<String, String> {
        val expectedParamKeys = pathComponents.filterIsInstance<PathParam>().map { it.key }

        val path = window.location.pathname
        val match = path.match(pathRegex)

        if (match != null && match.size == expectedParamKeys.size + 1) {
            val paramValues = match.drop(1).map { decodeURIComponent(it) }
            return expectedParamKeys.zip(paramValues).toMap().toCertainMap()
                .also { EzSpa.Logger.debug { "Path: $path, extracted params: $it" } }
        } else {
            error(
                "Incorrect match on path $path. Expected ${expectedParamKeys.size} params, " +
                        "pathSchema: $pathSchema, actual match: ${match?.joinToString()}"
            )
        }
    }

    /**
     * Construct the path portion of a link to this page, using the supplied path params.
     * Provided path param values will be URI-encoded. Example:
     *
     * [pathSchema] = "/ez/{par}/game"
     * [pathParams] = { "par": "my/param#" }
     * returns "/ez/my%2Fparam%23/game"
     *
     * @param pathParams must contain keys for all path params of this page's pathSchema
     */
    fun constructPathLink(pathParams: Map<String, String>): String {
        return pathComponents.joinToString(separator = "/", prefix = "/") {
            when (it) {
                is PathParam -> pathParams[it.key]?.encodeURIComponent() ?: error(
                    "Key ${it.key} not found while constructing path link. Page: $pageName," +
                            "pathSchema: $pathSchema, given params: $pathParams"
                )
                is PathString -> it.str
            }
        }
    }

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
