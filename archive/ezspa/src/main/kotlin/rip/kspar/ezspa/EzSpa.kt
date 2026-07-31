package rip.kspar.ezspa

import kotlinx.browser.window

object EzSpa {
    object Logger {
        var debugFunction: ((msgProvider: () -> Any?) -> Unit)? = null
        var warnFunction: ((msgProvider: () -> Any?) -> Unit)? = null
        var logPrefix: String = ""

        internal fun debug(msgProvider: () -> Any?): Unit? = debugFunction?.invoke { "$logPrefix${msgProvider()}" }
        internal fun warn(msgProvider: () -> Any?): Unit? = warnFunction?.invoke { "$logPrefix${msgProvider()}" }
    }

    object PageManager {
        var preUpdateHook: (() -> Unit)? = null
        var pageNotFoundHandler: ((String) -> Unit)? = null

        private var pages: List<Page> = emptyList()
        private var currentPage: Page? = null

        fun getCurrentPage() = pageFromPath(window.location.pathname, false)

        fun updatePage(pageState: String? = null) {
            preUpdateHook?.invoke()

            val path = window.location.pathname
            val newPage = pageFromPath(path, true)

            currentPage?.destruct()
            currentPage = newPage

            newPage.assertAuthorisation()
            newPage.clear()
            newPage.build(pageState)
        }

        fun navigateTo(path: String) {
            preNavigate()
            window.history.pushState(null, "", path)
            refreshCurrentPathFromBrowser()
            updatePage()
        }

        fun registerPages(newPages: List<Page>) {
            pages += newPages
        }

        internal fun preNavigate() {
            currentPage?.onPreNavigation()
        }

        private fun pageFromPath(path: String, respectNotFoundHandler: Boolean): Page {
            val matchingPages = pages.filter { it.pathMatches() }
            val matchingCount = matchingPages.size
            return when {
                matchingCount == 1 -> matchingPages.single()
                matchingCount < 1 -> {
                    if (respectNotFoundHandler)
                        pageNotFoundHandler?.invoke(path)
                    error("Path $path did not match any pages")
                }
                else -> error("Path $path matched several pages: ${matchingPages.map { it.pageName }}")
            }
        }
    }

    object Navigation {
        fun enableAnchorLinkInterception() {
            setupLinkInterception()
        }

        fun enableHistoryNavInterception() {
            setupHistoryNavInterception()
        }
    }
}

