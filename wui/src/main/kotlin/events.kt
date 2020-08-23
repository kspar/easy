import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.asList
import org.w3c.dom.events.MouseEvent

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
