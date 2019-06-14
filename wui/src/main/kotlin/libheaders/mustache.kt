@file:Suppress("INTERFACE_WITH_SUPERCLASS", "OVERRIDING_FINAL_MEMBER", "RETURN_TYPE_MISMATCH_ON_OVERRIDE", "CONFLICTING_OVERLOADS", "EXTERNAL_DELEGATION", "NESTED_CLASS_IN_EXTERNAL_INTERFACE")

import kotlin.js.RegExp

external interface MustacheStatic {
    var name: String
    var version: String
    var tags: Array<String>
    var Scanner: Any?
    var Context: Any?
    var Writer: Any?
    var escape: (value: String) -> String
    fun clearCache()
    fun parse(template: String, tags: Array<String>? = definedExternally /* null */): Any
    fun render(template: String, view: Any, partials: Any? = definedExternally /* null */, tags: Array<String>? = definedExternally /* null */): String
    fun render(template: String, view: MustacheContext, partials: Any? = definedExternally /* null */, tags: Array<String>? = definedExternally /* null */): String
    fun to_html(template: String, view: Any, partials: Any? = definedExternally /* null */, send: Any? = definedExternally /* null */): Any
    fun to_html(template: String, view: MustacheContext, partials: Any? = definedExternally /* null */, send: Any? = definedExternally /* null */): Any
}
external open class MustacheScanner(string: String) {
    open var string: String = definedExternally
    open var tail: String = definedExternally
    open var pos: Number = definedExternally
    open fun eos(): Boolean = definedExternally
    open fun scan(re: RegExp): String = definedExternally
    open fun scanUntil(re: RegExp): String = definedExternally
}
external open class MustacheContext {
    constructor(view: Any, parentContext: MustacheContext)
    constructor(view: Any)
    open var view: Any = definedExternally
    open var parentContext: MustacheContext = definedExternally
    open fun push(view: Any): MustacheContext = definedExternally
    open fun lookup(name: String): Any = definedExternally
}
external open class MustacheWriter {
    open fun clearCache(): Unit = definedExternally
    open fun parse(template: String, tags: Array<String>? = definedExternally /* null */): Any = definedExternally
    open fun render(template: String, view: Any, partials: Any, tags: Array<String>? = definedExternally /* null */): String = definedExternally
    open fun render(template: String, view: MustacheContext, partials: Any, tags: Array<String>? = definedExternally /* null */): String = definedExternally
    open fun renderTokens(tokens: Array<String>, context: MustacheContext, partials: Any, originalTemplate: Any): String = definedExternally
}
external var Mustache: MustacheStatic = definedExternally
