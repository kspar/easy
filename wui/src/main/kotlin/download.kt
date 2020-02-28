import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import kotlin.browser.document

fun Blob.saveAsFile(filename: String) {
    val url = URL.createObjectURL(this)
    val link = document.createElement("a") as HTMLAnchorElement
    link.href = url
    link.download = filename
    link.click()
    debug { "Saved file $filename" }
}
