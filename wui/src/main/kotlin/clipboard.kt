import kotlinx.browser.window
import kotlinx.coroutines.await
import rip.kspar.ezspa.doInPromise

fun copyToClipboard(text: String) = doInPromise {
    window.navigator.clipboard.writeText(text).await()
}
