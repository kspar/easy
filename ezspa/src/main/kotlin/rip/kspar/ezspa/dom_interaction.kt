package rip.kspar.ezspa

import org.w3c.dom.Node
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

            doInPromise {
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
        doInPromise {
            f(event)
        }
    })
}