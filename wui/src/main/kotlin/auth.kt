import kotlinx.browser.localStorage
import org.w3c.dom.get
import org.w3c.dom.set
import rip.kspar.ezspa.objOf
import kotlin.js.Promise

enum class Role(val id: String) {
    STUDENT("student"),
    TEACHER("teacher"),
    ADMIN("admin")
}

@JsName("Keycloak")
open external class InternalKeycloak(confUrl: String = definedExternally) {
    val token: String
    val tokenParsed: dynamic

    fun createAccountUrl(): String
    fun createLogoutUrl(options: dynamic = definedExternally): String

    protected fun init(options: dynamic): Promise<Boolean>
    protected fun login(options: dynamic = definedExternally)
    protected fun updateToken(minValidSec: Int): Promise<Boolean>
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
    val username: String
        get() = this.tokenParsed.preferred_username.unsafeCast<String>()

    lateinit var activeRole: Role

    fun hasRole(role: Role): Boolean = this.tokenParsed.easy_role.includes(role.id).unsafeCast<Boolean>()

    fun getAvailableRoles(): List<Role> = Role.values().filter { hasRole(it) }

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
            this.init(
                objOf(
                    "onLoad" to "check-sso",
                    "silentCheckSsoRedirectUri" to AppProperties.KEYCLOAK_SILENT_SSO_URL,
                    "pkceMethod" to "S256"
                )
            ).then { authenticated: Boolean ->
                debug { "Authenticated: $authenticated" }
                when {
                    authenticated -> {
                        activeRole = getPersistedRole() ?: getMainRole()
                        resolve(authenticated)
                    }
                    else -> {
                        debug { "Redirecting to login" }
                        login()
                    }
                }
            }.catch {
                permanentErrorMessage(
                    false,
                    UserMessageAction("Proovi uuesti", onActivate = ::login)
                ) { "Autentimine ebaõnnestus." }
                reject(RuntimeException("Authentication error"))
            }
        }


    fun makeSureTokenIsValid(): Promise<Boolean> =
        Promise { resolve, reject ->
            this.updateToken(AppProperties.KEYCLOAK_TOKEN_MIN_VALID_SEC).then { refreshed: Boolean ->
                if (refreshed) debug { "Refreshed tokens using refresh token" }
                resolve(refreshed)
            }.catch {
                debug { "Token refresh failed" }
                permanentErrorMessage(
                    true,
                    UserMessageAction("Logi sisse", onActivate = ::login)
                ) { "Sessiooni uuendamine ebaõnnestus. Jätkamiseks tuleb uuesti sisse logida." }
                reject(RuntimeException("Token refresh failed"))
            }
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
