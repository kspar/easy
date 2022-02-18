package libheaders

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
}

inline val Element.CodeMirror: CodeMirrorInstance?
    get() = asDynamic().CodeMirror
