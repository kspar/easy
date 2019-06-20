
@JsName("Keycloak")
open external class InternalKeycloak(confUrl: String = definedExternally) {
    val authenticated: Boolean
    val token: String
    val subject: String

    var onTokenExpired: dynamic
    var onAuthRefreshSuccess: dynamic

    fun init(options: dynamic): dynamic
    fun updateToken(minValidSec: Int): dynamic
}

// Expose keycloak instance via a singleton
object Keycloak : InternalKeycloak("https://easy-test-spa.cloud.ut.ee/static/keycloak.json")
