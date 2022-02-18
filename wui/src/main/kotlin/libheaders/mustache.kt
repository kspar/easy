package libheaders

@JsModule("mustache")
@JsNonModule
external object Mustache {
    fun render(template: String, data: dynamic): String
}
