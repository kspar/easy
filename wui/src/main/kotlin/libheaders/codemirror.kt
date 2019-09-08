package libheaders

import org.w3c.dom.Element

@JsName("CodeMirror")
external object CodeMirror {

    fun fromTextArea(element: Element, options: dynamic): CodeMirrorInstance
}

external class CodeMirrorInstance {
    fun getValue(): String
    fun setOption(key: String, value: Any?)
}
