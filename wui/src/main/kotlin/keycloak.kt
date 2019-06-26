import kotlin.browser.window

@JsName("Keycloak")
open external class InternalKeycloak(confUrl: String = definedExternally) {
    val authenticated: Boolean
    val token: String
    val tokenParsed: dynamic

    var onTokenExpired: dynamic
    var onAuthRefreshSuccess: dynamic

    fun init(options: dynamic): dynamic
    fun updateToken(minValidSec: Int): dynamic
}

// Expose keycloak instance via a singleton
object Keycloak : InternalKeycloak(AppProperties.KEYCLOAK_CONF_URL) {
    val firstName: String
        get() = this.tokenParsed.given_name.unsafeCast<String>()
    val lastName: String
        get() = this.tokenParsed.family_name.unsafeCast<String>()
    val email: String
        get() = this.tokenParsed.email.unsafeCast<String>()
}
