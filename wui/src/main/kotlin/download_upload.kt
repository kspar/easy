import components.DropdownMenuComp
import components.ToastIds
import components.ToastThing
import kotlinx.browser.document
import libheaders.TextDecoder
import libheaders.TypeError
import org.khronos.webgl.ArrayBuffer
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.asList
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.FileReader
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.objOf
import rip.kspar.ezspa.onChange
import translation.Str


fun saveTextAsFile(filename: String, content: String) {
    Blob(listOf(content).toTypedArray()).saveAsFile(filename)
}

fun Blob.saveAsFile(filename: String) {
    val url = URL.createObjectURL(this)
    val link = document.createElement("a") as HTMLAnchorElement
    link.href = url
    link.download = filename
    link.click()
    debug { "Saved file $filename" }
}

data class UploadedFile(val filename: String, val content: String)

fun uploadFile(callback: suspend (UploadedFile) -> Unit) {
    val el = document.createElement("input") as HTMLInputElement
    el.type = "file"

    el.onChange {
        val file = el.files?.asList()?.get(0)
        if (file != null) {

            if (file.size.toDouble() > 300000) {
                ToastThing(
                    Str.uploadErrorFileTooLarge,
                    icon = ToastThing.ERROR,
                    id = ToastIds.uploadedFileError
                )
                return@onChange
            }

            val reader = FileReader();
            reader.onload = {
                doInPromise {
                    val content = reader.result.unsafeCast<ArrayBuffer>()

                    try {
                        val text = TextDecoder("utf-8", objOf("fatal" to true)).decode(content)
                        val uploaded = UploadedFile(file.name, text)
                        callback(uploaded)

                    } catch (e: TypeError) {
                        ToastThing(
                            Str.uploadErrorFileNotText,
                            icon = ToastThing.ERROR,
                            id = ToastIds.uploadedFileError
                        )
                    }
                }
            }
            reader.readAsArrayBuffer(file);
        } else {
            debug { "File is null" }
        }
    }

    el.click()
}
