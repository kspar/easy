package queries

import org.w3c.dom.url.URL
import kotlin.browser.window


fun getCurrentQueryParamValue(key: String): String? {
    val searchParams = URL(window.location.href).searchParams
    return searchParams.get(key)
}

fun createQueryString(vararg params: Pair<String, String?>): String {
    val encodedParams = params.filter { (_, v) ->
        !v.isNullOrBlank()
    }.map { (k, v) ->
        encodeURIComponent(k) to encodeURIComponent(v!!)
    }

    return when {
        encodedParams.isEmpty() -> ""
        else -> encodedParams.joinToString("&", "?") { (k, v) -> "$k=$v" }
    }
}

external fun encodeURIComponent(str: String): String
