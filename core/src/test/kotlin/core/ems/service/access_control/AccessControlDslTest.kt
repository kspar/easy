package core.ems.service.access_control

import core.conf.security.EasyRole
import core.exception.ForbiddenException
import core.exception.ReqError
import core.testing.Auth
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * The `assertAccess { }` DSL itself, independent of any rule expressed in it.
 *
 * What is left is small, because the builder is now small. It was written for the `or` combinator,
 * which six of these tests pinned; `or` was deleted in EZ-1773 and they went with it. Its epitaph is
 * in `access_control_dsl.kt`, where the next person to want that facility will look — the short
 * version being that its ordering assumption and its choice of catchable exception were both wrong,
 * and nothing had called it in four years.
 *
 * That leaves the two properties every rule in this package depends on and none of them states:
 * **checks are conjunctive**, and **the first refusal wins** — nothing after it runs, which is what
 * makes it safe for a later check to assume an earlier one passed.
 *
 * Context-free: the DSL is pure control flow over lambdas, so this needs no database and runs on
 * every push. The rules built on it need rows and live in [AccessControlRulesTest].
 */
class AccessControlDslTest {

    private val caller = Auth.easyUser("someone", EasyRole.TEACHER)

    /**
     * A refusing check, and each call returns a **distinct** instance.
     *
     * The captured `id` is what forces that, and it is not decoration: `AccessCheck { }` captures
     * nothing, so the compiler is free to hand out one shared singleton for every occurrence — and
     * it does. Kept after the `or` tests were deleted because it is the kind of trap that costs an
     * hour once and nothing thereafter: a test that means to distinguish two checks has to actually
     * have two.
     */
    private var tag = 0
    private fun deny(): AccessCheck {
        val id = tag++
        return AccessCheck { throw ForbiddenException("no $id", ReqError.ROLE_NOT_ALLOWED) }
    }

    @Test
    fun `every check runs, and the first failure propagates`() {
        var ran = 0
        assertThrows(ForbiddenException::class.java) {
            caller.assertAccess {
                add { ran++ }
                add(deny())
                add { ran++ }
            }
        }
        assertEquals(1, ran) { "checks after a failing one must not run" }
    }

    @Test
    fun `all checks pass means all of them ran`() {
        var ran = 0
        caller.assertAccess {
            add { ran++ }
            add { ran++ }
            add { ran++ }
        }
        // The conjunctive half. Without it, a builder that evaluated only the first check would pass
        // the test above and every rule in this package would be enforcing one condition of several.
        assertEquals(3, ran)
    }

    @Test
    fun `no checks at all is allowed`() {
        caller.assertAccess { }
    }

    @Test
    fun `admin passes the admin check and a teacher does not`() {
        Auth.easyUser("a", EasyRole.ADMIN).assertAccess { admin() }

        val e = assertThrows(ForbiddenException::class.java) {
            Auth.easyUser("t", EasyRole.TEACHER).assertAccess { admin() }
        }
        assertEquals(ReqError.ROLE_NOT_ALLOWED, e.code)
    }
}
