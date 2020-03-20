import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.Node
import org.w3c.dom.asList
import org.w3c.dom.events.Event
import org.w3c.dom.events.MouseEvent

fun Node.onVanillaClick(preventDefault: Boolean, f: suspend (event: MouseEvent) -> Unit) {
    this.addEventListener("click", { event ->
        if (event is MouseEvent &&
                !event.defaultPrevented &&
                !event.altKey &&
                !event.ctrlKey &&
                !event.metaKey &&
                !event.shiftKey &&
                // Make sure the primary button was clicked, note that the 'click' event should fire only
                // for primary clicks in the future, currently it does in Chrome but does not in FF,
                // see https://developer.mozilla.org/en-US/docs/Web/Events#Mouse_events
                event.button.toInt() == 0) {

            if (preventDefault)
                event.preventDefault()

            MainScope().launch {
                f(event)
            }
        }
    })
}

fun List<Node>.onVanillaClick(preventDefault: Boolean, f: suspend (event: MouseEvent) -> Unit) {
    this.forEach {
        it.onVanillaClick(preventDefault, f)
    }
}

fun Node.onChange(f: (event: Event) -> Unit) {
    this.addEventListener("change", { event ->
        f(event)
    })
}

fun HTMLAnchorElement.onSingleClickWithDisabled(disabledText: String?, f: suspend (event: MouseEvent) -> Unit) {
    this.onVanillaClick(true) {
        val link = this
        MainScope().launch {
            val href = link.href
            link.removeAttribute("href")
            val activeHtml = link.innerHTML
            disabledText?.let {
                link.textContent = it
            }
            f(it)
            disabledText?.let {
                link.innerHTML = activeHtml
            }
            link.href = href
        }
    }
}

fun HTMLButtonElement.onSingleClickWithDisabled(disabledText: String, f: suspend (event: MouseEvent) -> Unit) {
    this.onVanillaClick(true) {
        val btn = this
        MainScope().launch {
            btn.disabled = true
            val btnContent = btn.getElementsByTagName("ez-btn-content").asList().single()
            val activeHtml = btnContent.innerHTML
            btnContent.textContent = disabledText
            f(it)
            btnContent.innerHTML = activeHtml
            btn.disabled = false
        }
    }
}
