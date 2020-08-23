import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.Node
import org.w3c.dom.PopStateEvent

private const val LOG_PREFIX = "NavInterceptor:"

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
        if (targetHost != currentHost) {
            EzSpa.Logger.debug { "$LOG_PREFIX Click not intercepted - destination is not local, target host: $targetHost, current host: $currentHost" }
            return@onVanillaClick
        }

        val targetUrl = anchorElement.href

        EzSpa.Logger.debug { "$LOG_PREFIX Intercepted click to local destination $targetUrl" }
        event.preventDefault()
        handleLocalLinkClick(targetUrl)
    }
}

internal fun setupHistoryNavInterception() {
    window.addEventListener("popstate", { event ->
        event as PopStateEvent
        val state = event.state
        if (state == null || state is String) {
            EzSpa.PageManager.updatePage(state as? String)
        } else {
            EzSpa.Logger.warn { "Page state was a non-null non-string:" }
            console.dir(state)
            EzSpa.PageManager.updatePage()
        }
    })
}

private fun handleLocalLinkClick(url: String) {
    EzSpa.PageManager.preNavigate()
    window.history.pushState(null, "", url)
    EzSpa.PageManager.updatePage()
}

private tailrec fun getClosestParentA(node: Node?): HTMLAnchorElement? = when {
    node == null -> null
    node.nodeName.toLowerCase() == "a" -> node as HTMLAnchorElement
    else -> getClosestParentA(node.parentNode)
}
