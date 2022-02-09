package libheaders

@JsName("CSV")
external object CSV {
    fun serialize(data: dynamic, conf: dynamic): String
}
