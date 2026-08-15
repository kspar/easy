package core.ems.service.access_control

import core.conf.security.EasyRole
import core.exception.ForbiddenException
import core.exception.InvalidRequestException
import core.exception.ReqError
import core.testing.Auth
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The `assertAccess { }` DSL itself, independent of any rule expressed in it.
 *
 * `AccessChecksBuilder.or` is the reason this exists. It is a control-flow contraption — it pops the
 * *last two* checks off a mutable list and reassembles them, on the assumption that both operands
 * were already added by the time the infix function runs — and nothing has ever exercised it.
 *
 * **And nothing calls it, either.** It has zero production call sites: the place that wants "teacher
 * on this course, or access to the library exercise" (`TeacherAutoassess`) spells it as a plain
 * `if/else` on whether a course id was given. So this file pins the behaviour of a facility that is
 * currently dead, which is worth knowing before preserving it — the live decision is whether to use
 * it or delete it (EZ-1773), not how to keep it working.
 *
 * Context-free: the DSL is pure control flow over lambdas, so this needs no database and runs on
 * every push. The rules built on it need rows and live in [AccessControlRulesTest].
 */
class AccessControlDslTest {

    private val caller = Auth.easyUser("someone", EasyRole.TEACHER)

    /**
     * A refusing check, and — like [allow] — each call returns a distinct instance.
     *
     * Same singleton trap, and it mattered here in a subtler way: with one shared instance,
     * `deny() or deny()` put the *same object* in both slots, so the test could not tell "both
     * operands were consulted" from "the first was evaluated twice", and `or`'s identity guard was
     * satisfied trivially. A version of `or` that ignored its argument entirely would have passed.
     */
    private fun deny(): AccessCheck {
        val id = tag++
        return AccessCheck { throw ForbiddenException("no $id", ReqError.ROLE_NOT_ALLOWED) }
    }

    /**
     * A permissive check, and each call returns a **distinct** instance.
     *
     * The capture is what forces that, and it is not decoration. `AccessCheck { }` captures nothing,
     * so the compiler is free to hand out one shared singleton for every occurrence — which it does.
     * The first version of the `or` guard test below built three "different" checks that way and
     * they were all the same object, so the identity comparison inside `or` succeeded and the test
     * that was supposed to observe a thrown `IllegalStateException` observed nothing at all.
     *
     * A test asserting on object identity has to actually have distinct objects.
     */
    private var tag = 0
    private fun allow(): AccessCheck {
        val id = tag++
        return AccessCheck { check(id >= 0) }
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
    fun `no checks at all is allowed`() {
        caller.assertAccess { }
    }

    @Test
    fun `or lets the caller through when the first operand passes`() {
        var secondRan = false
        caller.assertAccess {
            add(allow()) or add(AccessCheck { secondRan = true })
        }
        assertTrue(!secondRan) { "the second operand should not be consulted when the first passes" }
    }

    @Test
    fun `or lets the caller through when only the second operand passes`() {
        caller.assertAccess { add(deny()) or add(allow()) }
    }

    @Test
    fun `or refuses when both operands refuse`() {
        assertThrows(ForbiddenException::class.java) {
            caller.assertAccess { add(deny()) or add(deny()) }
        }
    }

    /**
     * `or` replaces the two checks it combines rather than adding a third.
     *
     * If it merely appended, the original failing operand would still be in the list and would still
     * be evaluated — so `deny() or allow()` would refuse, which is the opposite of what it means.
     */
    @Test
    fun `or replaces its operands rather than adding to them`() {
        var denyRuns = 0
        val counting = AccessCheck {
            denyRuns++
            throw ForbiddenException("no", ReqError.ROLE_NOT_ALLOWED)
        }
        caller.assertAccess { add(counting) or add(allow()) }
        assertEquals(1, denyRuns) { "the failing operand should run once, inside the combination" }
    }

    /**
     * The contraption's failure mode, pinned so that it stays a loud one.
     *
     * `or` assumes the two checks it is combining are the last two added. Combine checks that are
     * not, and it throws `IllegalStateException` — which is at least a crash rather than a silently
     * wrong access decision. Worth a test precisely because the guard is easy to delete while
     * "simplifying" the builder, and losing it would turn a crash into a wrong answer.
     */
    @Test
    fun `or rejects operands that are not the two most recently added`() {
        val first = allow()
        assertThrows(IllegalStateException::class.java) {
            caller.assertAccess {
                add(first)
                add(allow())
                add(allow())
                // `first` is no longer one of the last two.
                first or add(allow())
            }
        }
    }

    /**
     * `or` only recovers from [core.exception.AccessControlException]. Anything else propagates.
     *
     * This is the sharpest edge in the whole facility, and it is pinned here as **current
     * behaviour rather than as desirable behaviour**. `InvalidRequestException` extends
     * `RuntimeException` directly, so it is not caught — and `teacherOnCourse` throws exactly that,
     * via `assertCourseExists`, when the course id does not exist.
     *
     * So `teacherOnCourse(staleId) or libraryExercise(id, PR)` would answer 400 "course does not
     * exist" to a caller who has perfectly good library access and never needed the course at all.
     * That is a live trap for the first person to use `or`, which is why it is written down next to
     * the note that nobody uses it yet (EZ-1773).
     */
    @Test
    fun `or does not recover from a non-access exception`() {
        assertThrows(InvalidRequestException::class.java) {
            caller.assertAccess {
                add(AccessCheck { throw InvalidRequestException("no such course") }) or add(allow())
            }
        }
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
