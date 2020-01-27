/**
 * Hacks, formerly known as utilities
 */


@JsName("Object")
external class JsObject

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.toJsObj(): dynamic {
    val jsObject: dynamic = JsObject()
    this.forEach {
        // Hope that nested lists have & maps are Map<String, Any?>
        val jsValue =
                when (val value = it.value) {
                    is List<*> -> value.map { (it as Map<String, Any?>).toJsObj() }.toTypedArray()
                    is Map<*, *> -> (value as Map<String, Any?>).toJsObj()
                    else -> value
                }
        jsObject[it.key] = jsValue
    }
    return jsObject
}

fun List<Map<String, Any?>>.toJsObj(): dynamic =
        this.map { it.toJsObj() }.toTypedArray()


fun objOf(pair: Pair<String, Any?>): dynamic =
        mapOf(pair).toJsObj()

fun objOf(vararg pairs: Pair<String, Any?>): dynamic =
        mapOf(*pairs).toJsObj()


fun dynamicToAny(d: dynamic) = d.unsafeCast<Any>()

fun dynamicToAnyOrNull(d: dynamic) = d.unsafeCast<Any?>()
