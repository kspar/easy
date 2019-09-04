import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.events.MouseEvent

fun Node.onVanillaClick(f: (event: MouseEvent) -> Unit) {
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

            f(event)
            event.preventDefault()
        }
    })
}
