import kotlinx.serialization.DeserializationStrategy
import org.w3c.fetch.RequestInit
import org.w3c.fetch.Response
import kotlin.browser.window
import kotlin.js.Promise


enum class ReqMethod {
    GET,
    POST
}


val Response.http200: Boolean
    get() = status == 200.toShort()

val Response.http400: Boolean
    get() = status == 400.toShort()


fun <T> Response.parseTo(deserializer: DeserializationStrategy<T>): Promise<T> =
        this.text().then { JsonUtil.parse(deserializer, it) }


fun fetchEms(path: String, method: ReqMethod,
             data: Map<String, Any>? = null,
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
                                RequestInit(
                                        method.name,
                                        combinedHeaders.toJsObj(),
                                        jsonData))
                                .then(resolve)
                                .catch(reject)
                    }
                    .catch { reject }
        }
