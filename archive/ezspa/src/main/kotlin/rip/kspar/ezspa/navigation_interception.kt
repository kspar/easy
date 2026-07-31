package rip.kspar.ezspa

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.Node
import org.w3c.dom.PopStateEvent

private const val LOG_PREFIX = "NavInterceptor:"

/**
 * Contains the current URL path, including search and hash. Must be updated every time the path/search/hash changes.
 * Used for blocking popState (history navigation) if there are unsaved changes.
 */
lateinit var currentPath: String

fun refreshCurrentPathFromBrowser() {
    currentPath = window.location.pathname + window.location.search + window.location.hash
}

internal fun setupLinkInterception() {
    document.onVanillaClick(false) { event ->
        val target = event.target
        if (target !is Node) {
            return@onVanillaClick
        }

        // Find closest parent <a>
        val anchorElement = getClosestParentA(target)
            ?: return@onVanillaClick

        // Don't intercept links to external hosts
        val targetHost = anchorElement.hostname
        val currentHost = window.location.hostname
        val anchorTarget = anchorElement.target
        if (targetHost != currentHost) {
            EzSpa.Logger.debug { "$LOG_PREFIX Click not intercepted - destination is not local, target host: $targetHost, current host: $currentHost" }
            return@onVanillaClick
        }
        if (anchorTarget.isNotBlank()) {
            EzSpa.Logger.debug { "$LOG_PREFIX Click not intercepted - anchor target is not blank" }
            return@onVanillaClick
        }

        val targetPath = anchorElement.pathname + anchorElement.search + anchorElement.hash

        EzSpa.Logger.debug { "$LOG_PREFIX Intercepted click to local destination $targetPath" }
        event.preventDefault()
        handleLocalLinkClick(targetPath)
    }
}

internal fun setupHistoryNavInterception() {
    window.addEventListener("popstate", { event ->
        event as PopStateEvent
        val state = event.state

        if (!confirmIfUnsaved()) {
            // When popState fires, the browser URL path has already changed so if
            // the navigation is cancelled by the user, then we have to reverse the URL path change.
            // Note that currentPath has not changed yet, so it contains the previous path which we can recover.
            // This clears the forward history chain by pushing, would need insertAfterCurrent() in history API to avoid this.
            window.history.pushState(state, "", currentPath)
            return@addEventListener
        }

        refreshCurrentPathFromBrowser()

        if (state == null || state is String) {
            EzSpa.PageManager.updatePage(state as? String)
        } else {
            EzSpa.Logger.warn { "Page state was a non-null non-string:" }
            console.dir(state)
            EzSpa.PageManager.updatePage()
        }
    })
}

private fun handleLocalLinkClick(newPath: String) {
    if (!confirmIfUnsaved()) {
        return
    }
    EzSpa.PageManager.preNavigate()
    window.history.pushState(null, "", newPath)
    refreshCurrentPathFromBrowser()
    EzSpa.PageManager.updatePage()
}

private tailrec fun getClosestParentA(node: Node?): HTMLAnchorElement? = when {
    node == null -> null
    node.nodeName.lowercase() == "a" -> node as HTMLAnchorElement
    else -> getClosestParentA(node.parentNode)
}

private fun confirmIfUnsaved(): Boolean {
    return if (Navigation.hasUnsavedChanges) {
        // TODO: modal, should also offer to save
        window.confirm("Siin lehel on salvestamata muudatusi, kas soovid lahkuda?")
    } else {
        true
    }
}
