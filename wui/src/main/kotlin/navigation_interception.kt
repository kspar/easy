import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.Node
import org.w3c.dom.events.MouseEvent
import kotlin.browser.document
import kotlin.browser.window

const val LOG_PREFIX = "NavInterceptor:"

fun setupLinkInterception() {
    document.addEventListener("click", { event ->
        event as MouseEvent
        // Don't intercept clicks that are modified somehow
        if (event.defaultPrevented ||
                event.altKey ||
                event.ctrlKey ||
                event.metaKey ||
                event.shiftKey) {
            debug { "$LOG_PREFIX Click with modifier(s)" }
            return@addEventListener
        }

        val target = event.target
        if (target !is Node) {
            debug { "$LOG_PREFIX Click target is not a Node" }
            return@addEventListener
        }

        // Find closest parent <a>
        val anchorElement = getClosestParentA(target)
        if (anchorElement == null) {
            debug { "$LOG_PREFIX No anchor element parent found" }
            return@addEventListener
        }

        // Don't intercept links to external hosts
        val targetHost = anchorElement.hostname
        val currentHost = window.location.hostname
        if (targetHost != currentHost) {
            debug { "$LOG_PREFIX Link is not local, target host: $targetHost, current host: $currentHost" }
            return@addEventListener
        }

        val targetUrl = anchorElement.href

        debug { "$LOG_PREFIX Intercepted local link to $targetUrl" }
        event.preventDefault()
        handleLocalLinkClick(targetUrl)
    })
}

private fun handleLocalLinkClick(url: String) {
    window.history.pushState(null, "", url)
    updatePage()
}

private tailrec fun getClosestParentA(node: Node?): HTMLAnchorElement? = when {
    node == null -> null
    node.nodeName.toLowerCase() == "a" -> node as HTMLAnchorElement
    else -> getClosestParentA(node.parentNode)
}
