package core.ems.service.access_control

import core.conf.security.EasyUser


fun EasyUser.assertAccess(f: AccessChecksBuilder.() -> Unit) {
    val builder = AccessChecksBuilder(this)
    f(builder)
    builder.checkAll()
}


fun interface AccessCheck {
    fun validate(caller: EasyUser)
}

class AccessChecksBuilder(private val caller: EasyUser) {

    private val accessChecks: MutableList<AccessCheck> = mutableListOf()

    fun add(check: AccessCheck) {
        accessChecks.add(check)
    }

    fun checkAll() {
        accessChecks.forEach { it.validate(caller) }
    }

    /*
     * There was an `or` combinator here until EZ-1773. It had zero call sites in four years, and two
     * reasons not to keep waiting for one:
     *
     * It reached into this list and popped the *last two* entries, on the assumption that both
     * operands had been added immediately before the infix call, throwing IllegalStateException when
     * they had not. And it recovered only from AccessControlException — but InvalidRequestException
     * extends RuntimeException directly, and `teacherOnCourse` throws exactly that via
     * `assertCourseExists`. So `teacherOnCourse(id) or libraryExercise(id, PR)`, the one combination
     * anyone would plausibly want, answered 400 "course does not exist" to a caller with perfectly
     * good library access: the fallback silently did not happen.
     *
     * `TeacherAutoassess` wants that rule and spells it as a plain `if/else` on whether a course id
     * was given, which reads fine. If a second site ever needs it, write it as
     * `fun AccessChecksBuilder.either(a: AccessCheck, b: AccessCheck)` taking both operands as
     * arguments — no ordering assumption to violate — and decide deliberately which exception types
     * mean "this check said no".
     *
     * `testTrue()` and `testFalse()` went at the same time, and for a plainer reason: zero callers,
     * already `@Deprecated("For debugging only")`, and the coverage gate on this package was naming
     * them as its last two uncovered lines. `testFalse()` in particular is a hazard rather than a
     * convenience — a helper that refuses all access, living inside the access-control builder, one
     * forgotten line away from 403-ing an endpoint.
     */
}
