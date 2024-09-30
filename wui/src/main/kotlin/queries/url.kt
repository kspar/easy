package queries

import kotlinx.browser.window
import org.w3c.dom.url.URL
import rip.kspar.ezspa.encodeURIComponent


fun getCurrentQueryParamValue(key: String): String? {
    val searchParams = URL(window.location.href).searchParams
    return searchParams.get(key)
}

fun createQueryString(vararg params: Pair<String, String?>): String = createQueryString(false, params.toMap())

fun createQueryString(isSuffix: Boolean = false, params: Map<String, String?>): String {
    val encodedParams = params.map { (k, v) ->
        k.encodeURIComponent() to v?.encodeURIComponent()
    }

    val prefix = if (isSuffix) "&" else "?"

    return when {
        encodedParams.isEmpty() -> ""
        else -> encodedParams.joinToString("&", prefix) { (k, v) ->
            if (v == null)
                k
            else
                "$k=$v"
        }
    }
}
