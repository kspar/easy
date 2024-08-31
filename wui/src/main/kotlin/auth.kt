import components.ToastIds
import components.ToastThing
import rip.kspar.ezspa.objOf
import storage.Key
import storage.LocalStore
import translation.Str
import translation.activeLanguage
import kotlin.js.Promise

enum class Role(val id: String) {
    STUDENT("student"),
    TEACHER("teacher"),
    ADMIN("admin"),
}

@JsName("Keycloak")
open external class InternalKeycloak(confUrl: String = definedExternally) {
    val token: String
    val tokenParsed: dynamic
    val authenticated: Boolean

    fun createAccountUrl(options: dynamic = definedExternally): String
    fun createRegisterUrl(options: dynamic = definedExternally): String

    protected fun init(options: dynamic): Promise<Boolean>
    fun login(options: dynamic = definedExternally)
    fun logout(options: dynamic = definedExternally)
    protected fun updateToken(minValidSec: Int): Promise<Boolean>
}

// Expose keycloak instance via a singleton
// Note: Do not override methods or define methods with same name, javascript freezes because javascript
object Auth : InternalKeycloak(AppProperties.KEYCLOAK_CONF_URL) {
    val firstName: String?
        get() = this.tokenParsed?.given_name?.unsafeCast<String>()
    val lastName: String?
        get() = this.tokenParsed?.family_name?.unsafeCast<String>()
    val email: String?
        get() = this.tokenParsed?.email?.unsafeCast<String>()
    val username: String?
        get() = this.tokenParsed?.preferred_username?.unsafeCast<String>()

    lateinit var activeRole: Role

    fun hasRole(role: Role): Boolean = this.tokenParsed?.easy_role?.includes(role.id)?.unsafeCast<Boolean>() ?: false

    fun getAvailableRoles(): List<Role> = Role.values().filter { hasRole(it) }

    fun switchToRole(newRole: Role) {
        if (!hasRole(newRole)) {
            errorMessage { Str.somethingWentWrong }
            error("Role change to ${newRole.id} but user doesn't have that role")
        }
        activeRole = newRole
        LocalStore.set(Key.ACTIVE_ROLE, newRole.id)
    }


    fun initialize(isRequired: Boolean): Promise<Boolean> =
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
                    authenticated -> activeRole = getPersistedRole() ?: getMainRole()
                    else -> {
                        if (isRequired) {
                            debug { "Redirecting to login" }
                            login(objOf("locale" to activeLanguage.localeId))
                        }
                    }
                }
                resolve(authenticated)
            }.catch {
                ToastThing(
                    Str.authFailed,
                    ToastThing.Action(Str.tryAgain, { login(objOf("locale" to activeLanguage.localeId)) }),
                    icon = ToastThing.ERROR_INFO,
                    isDismissable = false,
                    displayTime = ToastThing.PERMANENT,
                    id = ToastIds.authFail
                )
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
                ToastThing(
                    Str.authRefreshFailed,
                    ToastThing.Action(Str.logIn, { login(objOf("locale" to activeLanguage.localeId)) }),
                    icon = ToastThing.ERROR_INFO,
                    displayTime = ToastThing.PERMANENT,
                    id = ToastIds.loginToContinue
                )
                reject(RuntimeException("Token refresh failed"))
            }
        }

    private fun getPersistedRole(): Role? {
        val persistedRoleStr = LocalStore.get(Key.ACTIVE_ROLE)
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
        else -> error("No valid roles found: ${this.tokenParsed?.easy_role}")
    }
}
