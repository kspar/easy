import org.w3c.dom.get
import org.w3c.dom.set
import kotlinx.browser.localStorage
import kotlin.js.Promise

enum class Role(val id: String) {
    STUDENT("student"),
    TEACHER("teacher"),
    ADMIN("admin")
}

@JsName("Keycloak")
open external class InternalKeycloak(confUrl: String = definedExternally) {
    val authenticated: Boolean
    val token: String
    val tokenParsed: dynamic

    var onTokenExpired: dynamic
    var onAuthRefreshSuccess: dynamic

    fun createAccountUrl(): String
    fun createLogoutUrl(options: dynamic = definedExternally): String
    protected fun init(options: dynamic): dynamic
    protected fun updateToken(minValidSec: Int): dynamic
}

// Expose keycloak instance via a singleton
// Note: Do not override methods or define methods with same name, javascript freezes because javascript
object Auth : InternalKeycloak(AppProperties.KEYCLOAK_CONF_URL) {
    val firstName: String
        get() = this.tokenParsed.given_name.unsafeCast<String>()
    val lastName: String
        get() = this.tokenParsed.family_name.unsafeCast<String>()
    val email: String
        get() = this.tokenParsed.email.unsafeCast<String>()

    lateinit var activeRole: Role

    fun hasRole(role: Role): Boolean = this.tokenParsed.easy_role.includes(role.id).unsafeCast<Boolean>()

    fun switchToRole(newRole: Role) {
        if (!hasRole(newRole)) {
            errorMessage { Str.somethingWentWrong() }
            error("Role change to ${newRole.id} but user doesn't have that role")
        }
        activeRole = newRole
        localStorage["activeRole"] = newRole.id
    }


    fun initialize(): Promise<Boolean> =
            Promise { resolve, reject ->
                this.init(objOf("onLoad" to "login-required"))
                        .success { authenticated: Boolean ->
                            debug { "Authenticated: $authenticated" }
                            activeRole = getPersistedRole() ?: getMainRole()
                            resolve(authenticated)
                        }
                        .error {
                            reject(RuntimeException("Authentication error"))
                        }
                Unit
            }


    fun makeSureTokenIsValid(): Promise<Boolean> =
            Promise { resolve, reject ->
                this.updateToken(AppProperties.KEYCLOAK_TOKEN_MIN_VALID_SEC)
                        .success { refreshed: Boolean ->
                            resolve(refreshed)
                        }
                        .error { error ->
                            warn { "Authentication error: $error" }
                            reject(RuntimeException("Authentication error"))
                        }
                Unit
            }

    private fun getPersistedRole(): Role? {
        val persistedRoleStr = localStorage["activeRole"]
        val persistedRole = Role.values().firstOrNull { it.id == persistedRoleStr }
        return when {
            persistedRole == null -> null
            hasRole(persistedRole) -> persistedRole
            else -> null
        }
    }

    private fun getMainRole(): Role = when {
        hasRole(Role.ADMIN) -> Role.ADMIN
        hasRole(Role.TEACHER) -> Role.TEACHER
        hasRole(Role.STUDENT) -> Role.STUDENT
        else -> error("No valid roles found: ${this.tokenParsed.easy_role}")
    }
}
