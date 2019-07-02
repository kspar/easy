import kotlin.js.Promise

enum class Role {
    STUDENT,
    TEACHER,
    ADMIN
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

    fun isStudent(): Boolean = this.tokenParsed.easy_role.includes("student").unsafeCast<Boolean>()
    fun isTeacher(): Boolean = this.tokenParsed.easy_role.includes("teacher").unsafeCast<Boolean>()
    fun isAdmin(): Boolean = this.tokenParsed.easy_role.includes("admin").unsafeCast<Boolean>()

    fun canToggleRole(): Boolean = (isTeacher() || isAdmin()) && isStudent()

    fun isMainRoleActive(): Boolean = getMainRole() == activeRole
    fun isStudentActive(): Boolean = activeRole == Role.STUDENT
    fun isTeacherActive(): Boolean = activeRole == Role.TEACHER
    fun isAdminActive(): Boolean = activeRole == Role.ADMIN

    fun getMainRole(): Role = when {
        isAdmin() -> Role.ADMIN
        isTeacher() -> Role.TEACHER
        isStudent() -> Role.STUDENT
        else -> error("No valid roles found: ${this.tokenParsed.easy_role}")
    }

    fun switchRoleToStudent() {
        if (!isStudent()) {
            errorMessage { Str.somethingWentWrong }
            error("Role change to student but user is not student")
        }
        activeRole = Role.STUDENT
    }

    fun switchRoleToMain() {
        activeRole = getMainRole()
    }

    fun initialize(): Promise<Boolean> =
            Promise { resolve, reject ->
                this.init(objOf("onLoad" to "login-required"))
                        .success { authenticated: Boolean ->
                            debug { "Authenticated: $authenticated" }
                            activeRole = getMainRole()
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

}
