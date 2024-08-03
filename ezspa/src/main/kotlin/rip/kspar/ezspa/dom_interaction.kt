package rip.kspar.ezspa

import kotlinx.browser.document
import kotlinx.coroutines.await
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent
import kotlin.js.Date

class ActiveListener internal constructor(val node: Node, val eventType: String, val listener: (Event) -> Unit) {
    fun remove() {
        node.removeEventListener(eventType, listener)
    }
}

fun Node.onVanillaClick(preventDefault: Boolean, f: suspend (event: MouseEvent) -> Unit): ActiveListener {
    val listener: (Event) -> Unit = { event: Event ->
        if (event is MouseEvent &&
            !event.defaultPrevented &&
            !event.altKey &&
            !event.ctrlKey &&
            !event.metaKey &&
            !event.shiftKey &&
            // Make sure the primary button was clicked, note that the 'click' event should fire only
            // for primary clicks in the future, currently it does in Chrome but does not in FF,
            // see https://developer.mozilla.org/en-US/docs/Web/Events#Mouse_events
            event.button.toInt() == 0
        ) {

            if (preventDefault)
                event.preventDefault()

            doInPromise {
                f(event)
            }
        }
    }

    this.addEventListener("click", listener)
    return ActiveListener(this, "click", listener)
}

fun List<Node>.onVanillaClick(preventDefault: Boolean, f: suspend (event: MouseEvent) -> Unit) {
    this.forEach {
        it.onVanillaClick(preventDefault, f)
    }
}

fun Node.onChange(f: suspend (event: Event) -> Unit): ActiveListener {
    val listener: (Event) -> Unit = { event: Event ->
        doInPromise {
            f(event)
        }
    }
    this.addEventListener("change", listener)
    return ActiveListener(this, "change", listener)
}


fun Node.onInput(debounceDelay: Int = 250, f: suspend (event: Event) -> Unit): ActiveListener {
    val listener = createEventListenerWithDebounce(debounceDelay, f)
    this.addEventListener("input", listener)
    return ActiveListener(this, "input", listener)
}

fun Element.onFocus(f: suspend (event: Event) -> Unit): ActiveListener {
    val listener: (Event) -> Unit = { event: Event ->
        doInPromise {
            f(event)
        }
    }
    this.addEventListener("focus", listener)
    return ActiveListener(this, "focus", listener)
}

fun Element.onBlur(f: suspend (event: Event) -> Unit): ActiveListener {
    val listener: (Event) -> Unit = { event: Event ->
        doInPromise {
            f(event)
        }
    }
    this.addEventListener("blur", listener)
    return ActiveListener(this, "blur", listener)
}

fun Element.onENTER(f: suspend (KeyboardEvent) -> Unit): ActiveListener {
    val listener = { e: Event ->
        doInPromise {
            e as KeyboardEvent
            if (e.keyCode == 13 && !e.repeat) {
                f(e)
                e.preventDefault()
            }
        }
        Unit
    }
    this.addEventListener("keydown", listener)
    return ActiveListener(this, "keydown", listener)
}

fun createEventListenerWithDebounce(debounceDelay: Int, handler: suspend (event: Event) -> Unit): (Event) -> Unit {
    var isWaiting = false
    var lastCallTime = Date.now()
    lateinit var latestEvent: Event

    val listener: (Event) -> Unit = { event: Event ->
        doInPromise {
            latestEvent = event

            if (!isWaiting) {
                isWaiting = true
                val sleepTimeMs = (lastCallTime + debounceDelay - Date.now()).toInt()

                if (sleepTimeMs > 0) {
                    sleep(sleepTimeMs).await()
                }

                handler(latestEvent)

                lastCallTime = Date.now()
                isWaiting = false
            }
        }
    }

    return listener
}

fun Element.appendHTML(html: String) = this.insertAdjacentHTML("beforeend", html)

fun plainDstStr(vararg dstIds: String?): String = plainDstStr(dstIds.toList())
fun plainDstStr(dstIds: List<String?>): String =
    dstIds.filterNotNull().joinToString("\n") { """<ez-dst id="$it"></ez-dst>""" }

fun sanitiseHTML(text: String): String {
    val tmpElement = document.createElement("div")
    tmpElement.textContent = text;
    return tmpElement.innerHTML;
}
