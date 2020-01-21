import kotlinx.serialization.DeserializationStrategy
import org.w3c.fetch.Response
import spa.PageManager
import kotlin.browser.window
import kotlin.js.Promise


enum class ReqMethod {
    GET,
    POST,
    PUT
}


val Response.http200: Boolean
    get() = status == 200.toShort()

val Response.http204: Boolean
    get() = status == 204.toShort()

val Response.http400: Boolean
    get() = status == 400.toShort()


fun <T> Response.parseTo(deserializer: DeserializationStrategy<T>): Promise<T> =
        this.text().then { JsonUtil.parse(deserializer, it) }


fun fetchEms(path: String, method: ReqMethod,
             data: Map<String, Any?>? = null,
             headers: Map<String, String> = emptyMap()): Promise<Response> =

        Promise { resolve, reject ->
            Auth.makeSureTokenIsValid()
                    .then {
                        val defaultHeaders = mapOf(
                                "Authorization" to "Bearer ${Auth.token}",
                                "Content-Type" to "application/json")

                        val combinedHeaders = defaultHeaders + headers

                        val jsonData = if (data == null) null else JSON.stringify(dynamicToAny(data.toJsObj()))

                        window.fetch(AppProperties.EMS_ROOT + path,
                                objOf(
                                        "method" to method.name,
                                        "headers" to combinedHeaders,
                                        "body" to jsonData,
                                        "signal" to PageManager.getNavCancelSignal()
                                ))
                                .then(resolve)
                                // TODO: check if error is AbortError -> just log
                                .catch(reject)
                    }
                    .catch(reject)
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

external class AbortController {
    val signal: AbortSignal

    fun abort()
}

external class AbortSignal
