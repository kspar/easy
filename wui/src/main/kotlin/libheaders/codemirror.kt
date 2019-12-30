package libheaders

import org.w3c.dom.Element

@JsName("CodeMirror")
external object CodeMirror {
    var modeURL: String
    fun fromTextArea(element: Element, options: dynamic): CodeMirrorInstance
    fun autoLoadMode(editor: CodeMirrorInstance, mode: String)

    @JsName("Doc")
    class Doc(value: String, mode: String)
}

external class CodeMirrorInstance {
    fun getValue(): String
    fun setValue(value: String)
    fun setOption(key: String, value: Any?)
    fun swapDoc(newDoc: CodeMirror.Doc): CodeMirror.Doc
}

inline val Element.CodeMirror: CodeMirrorInstance?
    get() = asDynamic().CodeMirror
