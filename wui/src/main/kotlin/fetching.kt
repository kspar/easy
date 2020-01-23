import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.w3c.fetch.Response
import spa.PageManager
import kotlin.browser.window
import kotlin.js.Promise


@Serializable
data class ErrorBody(
        val id: String,
        val code: String? = null,
        val attrs: Map<String, String>,
        val log_msg: String
)

enum class ReqMethod {
    GET,
    POST,
    PUT
}


val Response.http200: Boolean
    get() = status == 200.toShort()

val Response.http204: Boolean
    get() = status == 204.toShort()


fun <T> Response.parseTo(deserializer: DeserializationStrategy<T>): Promise<T> =
        text().then { it.parseTo(deserializer) }


fun fetchEms(path: String, method: ReqMethod,
             data: Map<String, Any?>? = null,
             headers: Map<String, String> = emptyMap(),
             // TODO: default successChecker is a temp hack before refactoring every call
             successChecker: (Response) -> Boolean = { r: Response -> true },
             vararg errorHandlers: (ErrorBody?, Response) -> Boolean = emptyArray()): Promise<Response> =

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
                                .then { resp ->
                                    if (successChecker(resp.clone())) {
                                        resolve(resp)
                                    } else {
                                        resp.clone().parseTo(ErrorBody.serializer())
                                                .catch {
                                                    if (it is SerializationException) {
                                                        debug { "SerializationException: $it" }
                                                        // No error body
                                                        null
                                                    } else {
                                                        warn { "Exception while parsing: $it" }
                                                        throw it
                                                    }
                                                }
                                                .then { errorBody: ErrorBody? ->
                                                    if (errorHandlers.none { it(errorBody, resp.clone()) }) {
                                                        debug { "Calling default error handler" }
                                                        defaultRespErrorHandler(errorBody, resp.clone())
                                                    }
                                                }

                                        throw HandledResponseError()
                                    }
                                }

                                .catch {
                                    // TODO: check if error is AbortError -> just log
                                    reject(it)
                                }
                    }
        }


class HandledResponseError : Exception()

fun defaultRespErrorHandler(errorBody: ErrorBody?, resp: Response) {
    val status = resp.status
    if (errorBody == null) {
        resp.text().then { body ->
            errorMessage {
                """Midagi läks valesti, palun proovi hiljem uuesti. 
                    |Server tagastas ootamatu vastuse:
                    |HTTP staatus: $status
                    |Vastus: ${body.truncate(150)}
                """.trimMargin()
            }
        }.catch {
            errorMessage { "Midagi läks valesti, palun proovi hiljem uuesti. Server tagastas ootamatu vastuse HTTP staatusega $status." }
        }
    } else {
        errorMessage {
            """Midagi läks valesti, palun proovi hiljem uuesti. 
                |Server tagastas vea:
                |HTTP staatus: $status
                |Kood: ${errorBody.code}
                |Sõnum: ${errorBody.log_msg}
            """.trimMargin()
        }
    }
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

fun String.truncate(n: Int) = if (this.length <= n) this else "${this.take(n - 3)}..."
