package core.ems.service.code.similarity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The rules every parallel part of a comparison runs by, each held to account on its own.
 *
 * Every task below that stands in for real work busy-waits rather than sleeps. Real work here - a
 * parse, a tiling - does not notice an interrupt, and a sleep does: a test built on sleeps would pass
 * against code that interrupts its tasks and hopes, which is exactly the code this replaced.
 */
@Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SimilarityWorkersTest {

    private fun workers(threads: Int) = SimilarityWorkers(threads, 1L shl 20)

    /** Keeps a thread busy for [millis], deaf to interrupts, the way a parse is. */
    private fun busy(millis: Long, until: () -> Boolean = { false }) {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis)
        while (System.nanoTime() - end < 0 && !until()) Thread.onSpinWait()
    }

    /** What a task threw, caught by hand: assertThrows rethrows an OutOfMemoryError rather than return it. */
    private fun failureOf(block: () -> Unit): Throwable? = try {
        block()
        null
    } catch (t: Throwable) {
        t
    }

    @Test
    fun `results come back in the order the tasks were given, whatever order they finish in`() {
        val results = workers(4).use { workers ->
            workers.all((0 until 20).map { i -> { busy((20 - i) * 2L); i * i } })
        }
        assertEquals((0 until 20).map { it * it }, results)
    }

    @Test
    fun `after a task fails, a task that has not started does not run`() {
        val ran = Collections.synchronizedList(mutableListOf<Int>())
        val thrown = assertThrows<IllegalStateException> {
            workers(1).use { workers ->
                workers.all((0 until 10).map { i -> { ran += i; if (i == 2) error("task 2"); i } })
            }
        }
        assertEquals("task 2", thrown.message)
        assertEquals(listOf(0, 1, 2), ran, "tasks queued behind the failure ran anyway")
    }

    @Test
    fun `a failure is reported only once every task running beside it has finished`() {
        // Otherwise the caller - and the permit behind it - moves on while this run still holds a
        // thread and its heap. The slow task cannot be interrupted, like the parse it stands in for.
        // Checked the moment all() throws, before close(), which waits too and would hide it; and with
        // the failing task first, so that reading the results in order cannot pass for waiting.
        val slowFinished = AtomicBoolean()
        val slowStarted = CountDownLatch(1)
        workers(2).use { workers ->
            val thrown = failureOf {
                workers.all(
                    listOf(
                        { slowStarted.await(); error("fails while the other runs") },
                        { slowStarted.countDown(); busy(300); slowFinished.set(true) },
                    ),
                )
            }
            assertTrue(thrown is IllegalStateException, "all() threw $thrown")
            assertTrue(slowFinished.get(), "all() threw while a task was still running")
        }
    }

    @Test
    fun `a long task sees the stop, and gives up between its units of work`() {
        val gaveUpEarly = AtomicBoolean()
        val started = CountDownLatch(1)
        val took = System.nanoTime()
        assertThrows<IllegalStateException> {
            workers(2).use { workers ->
                workers.all(
                    listOf(
                        {
                            started.countDown()
                            for (unit in 0 until 1_000) {
                                if (workers.stopped) {
                                    gaveUpEarly.set(true)
                                    break
                                }
                                busy(10)
                            }
                        },
                        { started.await(); error("stop the other") },
                    ),
                )
            }
        }
        assertTrue(gaveUpEarly.get(), "the long task never saw the stop")
        assertTrue(System.nanoTime() - took < TimeUnit.SECONDS.toNanos(5), "the long task ran its course")
    }

    @Test
    fun `the worst failure is the one reported, whichever task threw it and whenever`() {
        // A timeout reported over an OutOfMemoryError tells the caller "try a smaller group" over a
        // dead heap, and nobody is told. The order of the tasks is varied so that list order and
        // finishing order both disagree with severity somewhere.
        val oom = OutOfMemoryError("synthetic")
        val bug = IllegalStateException("a bug")
        val late = SimilarityTimedOut(1)
        for (order in listOf(listOf<Throwable>(late, bug, oom), listOf(oom, late, bug), listOf(bug, oom, late))) {
            val thrown = failureOf {
                workers(3).use { workers ->
                    val gate = CountDownLatch(3)
                    workers.all(order.map { failure -> { gate.countDown(); gate.await(); throw failure } })
                }
            }
            assertSame(oom, thrown, "for $order")
        }
        // And without an Error, a bug is reported over running out of time. Gated, so both do throw:
        // a task not yet started when the first fails is skipped instead.
        val gate = CountDownLatch(2)
        val thrown = failureOf {
            workers(2).use { it.all(listOf({ gate.countDown(); gate.await(); throw late }, { gate.countDown(); gate.await(); throw bug })) }
        }
        assertSame(bug, thrown)
        assertTrue(bug.suppressed.contains(late), "the timeout was not attached to what was reported")
        // And a refusal at the run's pace over a timeout, in either order: one worker's refusal reaches the
        // others only once it has left it, and one that hit the deadline meanwhile was reported instead.
        val costly = SimilarityTooCostly(SimilarityProjection(2_000_000_000L, 1, preparing = false))
        for (order in listOf(listOf<Throwable>(late, costly), listOf(costly, late))) {
            val both = CountDownLatch(2)
            val reported = failureOf { workers(2).use { workers -> workers.all(order.map { failure -> { both.countDown(); both.await(); throw failure } }) } }
            assertSame(costly, reported, "for $order")
        }
    }

    @Test
    fun `a caller interrupted while waiting is released only once the running tasks are done`() {
        // A server shutting down interrupts its request threads. The request must not then leave its
        // tasks running behind it: close() waits them out, and the permit is released after that.
        val taskStarted = CountDownLatch(1)
        // The task runs on until the interrupt has been sent, and ignores it, as a parse does. It used to
        // run a fixed 500 ms, and a test thread descheduled that long let it finish first: `all()` then
        // returned normally and the test failed for a reason that was nobody's bug.
        val interruptSent = AtomicBoolean()
        val taskFinished = AtomicBoolean()
        var failure: Throwable? = null
        var finishedWhenClosed = false
        val caller = thread {
            try {
                workers(1).use { workers ->
                    try {
                        workers.all(listOf({
                            taskStarted.countDown()
                            while (!interruptSent.get()) Thread.onSpinWait()
                            busy(200)
                            taskFinished.set(true)
                        }))
                    } catch (e: InterruptedException) {
                        failure = e
                    }
                }
                finishedWhenClosed = taskFinished.get()
            } catch (t: Throwable) {
                failure = t
            }
        }
        assertTrue(taskStarted.await(10, TimeUnit.SECONDS), "the task never started")
        caller.interrupt()
        interruptSent.set(true)
        caller.join(10_000)

        assertTrue(failure is InterruptedException, "the interrupted caller got $failure")
        assertTrue(finishedWhenClosed, "close() returned while the task was still running")
    }

    @Test
    fun `tasks run on threads with the stack they were given`() {
        // The pool's stack is what lets a parser follow nesting a default stack overflows on. Recursing
        // a few hundred thousand frames overflows 1 MB and fits in 64.
        fun depth(n: Int): Int = if (n == 0) 0 else 1 + depth(n - 1)
        val deep = SimilarityWorkers(1, 64L shl 20).use { it.all(listOf({ depth(200_000) })) }.single()
        assertEquals(200_000, deep)
        val shallow = failureOf { workers(1).use { it.all(listOf({ depth(200_000) })) } }
        assertTrue(shallow is StackOverflowError, "a 1 MB stack held 200,000 frames: $shallow")
    }

    @Test
    fun `the threads are named for the run they belong to`() {
        val names = workers(2).use { workers -> workers.all(listOf({ Thread.currentThread().name }, { Thread.currentThread().name })) }
        val other = workers(1).use { workers -> workers.all(listOf({ Thread.currentThread().name })) }.single()
        assertTrue(names.all { it.startsWith("similarity-") }, "$names")
        val run = { name: String -> name.split('-')[1] }
        assertFalse(run(other) in names.map(run), "two runs share a thread name: $names and $other")
    }

}
