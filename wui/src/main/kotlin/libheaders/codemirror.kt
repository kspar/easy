package libheaders

import rip.kspar.ezspa.objOf
import org.w3c.dom.Element

@JsName("CodeMirror")
external object CodeMirror {
    var modeURL: String
    fun fromTextArea(element: Element, options: dynamic): CodeMirrorInstance
    fun autoLoadMode(editor: CodeMirrorInstance, mode: dynamic)

    @JsName("Doc")
    class Doc(value: String, mode: dynamic) {
        fun getValue(): String
        fun setValue(value: String?)
    }
}

external class CodeMirrorInstance {
    fun getValue(): String
    fun setValue(value: String)
    fun setOption(key: String, value: dynamic)
    fun swapDoc(newDoc: CodeMirror.Doc): CodeMirror.Doc
    fun getMode(): CodeMirrorMode
    fun execCommand(cmd: String)
    fun somethingSelected(): Boolean
}

external class CodeMirrorMode {
    val name: String?
}

inline val Element.CodeMirror: CodeMirrorInstance?
    get() = asDynamic().CodeMirror


// Move to CodeEditorComp once everything has migrated
val tabHandler = objOf(
    "Tab" to { cm: CodeMirrorInstance ->
        when {
            cm.getMode().name == null -> cm.execCommand("insertTab")
            cm.somethingSelected() -> cm.execCommand("indentMore")
            else -> cm.execCommand("insertSoftTab")
        }
    },
    "Shift-Tab" to { cm: CodeMirrorInstance ->
        cm.execCommand("indentLess")
    }
)
