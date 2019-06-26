import org.w3c.fetch.RequestInit
import org.w3c.fetch.Response
import kotlin.browser.window
import kotlin.js.Promise

const val EMS_ROOT = "https://dev.ems.lahendus.ut.ee/v2"

enum class ReqMethod {
    GET,
    POST
}

fun fetchEms(path: String, method: ReqMethod,
             data: Map<String, Any>? = null,
             headers: Map<String, String> = emptyMap()): Promise<Response> {

    val defaultHeaders = mapOf(
            "Authorization" to "Bearer ${Keycloak.token}",
            "Content-Type" to "application/json")

    val combinedHeaders = defaultHeaders + headers

    val jsonData = if (data == null) "" else JSON.stringify(dynamicToAny(data.toJsObj()))

    return window.fetch(EMS_ROOT + path,
            RequestInit(
                    method.name,
                    combinedHeaders.toJsObj(),
                    jsonData))
}
