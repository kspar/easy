package spa

import debug
import onVanillaClick
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.Node
import org.w3c.dom.PopStateEvent
import warn
import kotlin.browser.document
import kotlin.browser.window

const val LOG_PREFIX = "NavInterceptor:"

fun setupLinkInterception() {
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
            debug { "$LOG_PREFIX Click not intercepted - destination is not local, target host: $targetHost, current host: $currentHost" }
            return@onVanillaClick
        }

        val targetUrl = anchorElement.href

        debug { "$LOG_PREFIX Intercepted click to local destination $targetUrl" }
        event.preventDefault()
        handleLocalLinkClick(targetUrl)
    }
}

fun setupHistoryNavInterception() {
    window.addEventListener("popstate", { event ->
        event as PopStateEvent
        val state = event.state
        if (state == null || state is String) {
            PageManager.updatePage(state as? String)
        } else {
            warn { "Page state was a non-null non-string:" }
            console.dir(state)
            PageManager.updatePage()
        }
    })
}

private fun handleLocalLinkClick(url: String) {
    window.history.pushState(null, "", url)
    PageManager.updatePage()
}

private tailrec fun getClosestParentA(node: Node?): HTMLAnchorElement? = when {
    node == null -> null
    node.nodeName.toLowerCase() == "a" -> node as HTMLAnchorElement
    else -> getClosestParentA(node.parentNode)
}
