package core.ems.service.code.similarity

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * A clock that moves only when the test moves it (EZ-1667). A test of a budget used to race a sleep
 * against the work, and on a loaded machine the work lost: JPlag's tokenising alone outlasted a three-second
 * budget once in the full suite, and the run was refused before the pair the test was about.
 */
internal class ManualTicker : Ticker {
    private val now = AtomicLong()

    override fun nanos(): Long = now.get()

    fun advance(millis: Long) {
        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis))
    }

    val millis: Long get() = TimeUnit.NANOSECONDS.toMillis(now.get())
}

/**
 * A clock of each thread's own, moved only by that thread: the time each worker itself spent, so that what
 * one worker's slow task takes is not the others' time as well, as it is on a shared clock.
 */
internal class PerThreadTicker : Ticker {
    private val now = ThreadLocal.withInitial { AtomicLong() }

    override fun nanos(): Long = now.get().get()

    fun advance(millis: Long) {
        now.get().addAndGet(TimeUnit.MILLISECONDS.toNanos(millis))
    }
}

/**
 * A clock that moves [stepNanos] each time it is read: work that reads the time as it goes - every pair,
 * every few rows of a long one - spends it in proportion, on any machine, for a provider with no hook of
 * its own to slow down.
 */
internal class SteppingTicker(private val stepNanos: Long) : Ticker {
    private val now = AtomicLong()

    override fun nanos(): Long = now.addAndGet(stepNanos)
}
