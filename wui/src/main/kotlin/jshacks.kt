/**
 * Hacks, formerly known as utilities
 */


@JsName("Object")
external class JsObject

fun Map<String, Any?>.toJsObj(): dynamic {
    val jsObject: dynamic = JsObject()
    this.forEach {
        jsObject[it.key] = it.value
    }
    return jsObject
}

fun objOf(pair: Pair<String, Any>): dynamic =
        mapOf(pair).toJsObj()

fun objOf(vararg pairs: Pair<String, Any>): dynamic =
        mapOf(*pairs).toJsObj()


fun dynamicToAny(d: dynamic) = d.unsafeCast<Any>()

fun dynamicToAnyOrNull(d: dynamic) = d.unsafeCast<Any?>()
