package queries

import AppProperties
import Auth
import debug
import kotlinx.browser.window
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import org.w3c.fetch.Response
import parseTo
import parseToOrNull
import rip.kspar.ezspa.*
import warn
import kotlin.js.Promise

enum class ReqMethod {
    GET,
    POST,
    PUT,
    PATCH,
    DELETE,
}

// Overloading because vararg doesn't work for named params (error handlers)
fun fetchEms(
    path: String, method: ReqMethod, data: Map<String, Any?>? = null, headers: Map<String, String> = emptyMap(),
    successChecker: RespSuccessChecker, errorHandler: RespErrorHandler, cancellable: Boolean = true
): Promise<Response> =
    fetchEms(path, method, data, headers, successChecker, listOf(errorHandler), cancellable)


fun fetchEms(
    path: String, method: ReqMethod,
    data: Map<String, Any?>? = null,
    headers: Map<String, String> = emptyMap(),
    successChecker: RespSuccessChecker,
    errorHandlers: List<RespErrorHandler> = emptyList(),
    cancellable: Boolean = true
): Promise<Response> =

    Promise { resolve, reject ->
        Auth.makeSureTokenIsValid()
            .then {
                val defaultHeaders = mapOf(
                    "Authorization" to "Bearer ${Auth.token}",
                    "Content-Type" to "application/json"
                )

                val combinedHeaders = defaultHeaders + headers

                val jsonData = if (data == null) null else JSON.stringify(dynamicToAny(data.toJsObj()))

                window.fetch(
                    AppProperties.EMS_ROOT + path,
                    // RequestInit doesn't have signal
                    objOf(
                        "method" to method.name,
                        "headers" to combinedHeaders,
                        "body" to jsonData,
                        "signal" to if (cancellable) getNavCancelSignal() else null
                    )
                )
                    .then { resp ->
                        if (successChecker(resp.clone())) {
                            resolve(resp)
                        } else {
                            val handlerException = resp.clone().parseTo(ErrorBody.serializer())
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
                                    if (errorHandlers.none { resp.clone().it(errorBody) }) {
                                        ErrorHandlers.defaultMsg(resp, errorBody)
                                    }
                                }
                                .catch {
                                    // error handler threw
                                    it
                                }

                            throw HandledResponseError(handlerException)
                        }
                    }

                    .catch {
                        // TODO: check if error is AbortError -> just log
                        reject(it)
                    }
            }
    }


val Response.http200: Boolean
    get() = status == 200.toShort()

val Response.http204: Boolean
    get() = status == 204.toShort()

fun <T> Response.parseTo(deserializer: DeserializationStrategy<T>): Promise<T> =
    text().then { it.parseTo(deserializer) }

fun <T> Response.parseToOrNull(deserializer: DeserializationStrategy<T>): Promise<T?> =
    text().then { it.parseToOrNull(deserializer) }


private var abortControllers = mutableListOf<AbortController>()

fun getNavCancelSignal(): AbortSignal =
    AbortController().also {
        abortControllers.add(it)
    }.signal

fun abortAllFetchesAndClear() {
    abortControllers.forEach { it.abort() }
    abortControllers.clear()
}
