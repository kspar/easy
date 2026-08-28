package core.aas

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [FunctionScheduler]'s bookkeeping when the coroutine waiting on a job goes away.
 *
 * **`jobs` is the admission control for the whole grading pipeline**, which is why this is worth a test
 * rather than a shrug. `AutoGradeScheduler.grade` admits `min(waiting, maxLoad - countActive())`,
 * `chooseOptimalExecutor` ranks executors by `size() / maxLoad`, and `deleteExecutor` refuses while
 * `size()` is non-zero. `maxLoad` is the one number that stops core overloading a grading host, and it
 * is computed entirely from this queue.
 *
 * `scheduleAndAwait` ended with `finally { jobs.remove(job) }` — removal with no cancellation. On the
 * normal and the error paths the job is already finished and removing it is right. But if the *awaiting*
 * coroutine is cancelled while a started job is still in flight, the job was dropped from the queue
 * while it was still occupying a slot on the executor, so core's idea of that executor's load fell
 * below the truth and it could admit past `maxLoad`.
 *
 * **The scheduled function is a blocking call, and that is what decides the fix.** It is
 * `callExecutor` — a synchronous `RestTemplate` POST whose read timeout defaults to an hour. Coroutine
 * cancellation is cooperative, so cancelling the `Deferred` cannot interrupt it: the request keeps
 * running to completion or timeout either way. Cancelling on the way out therefore does *not* fix the
 * undercount, because the removal is what causes it and the work does not stop. What fixes it is
 * keeping a started job in the queue until it genuinely completes.
 *
 * Cancellation is still right for a job that was never *started*: nobody is waiting for its result any
 * more, and without it `startNext()` would later hand a grading slot to work whose answer is already
 * discarded.
 *
 * Realistic trigger, kept in view so this is not overstated: `submitAndAwait` is reached through
 * `runBlocking` from a blocking servlet thread, and an executor failure completes the job exceptionally
 * rather than cancelling the waiter. JVM shutdown is the main way to get here.
 */
class FunctionSchedulerTest {

    private val started = CountDownLatch(1)
    private val release = CountDownLatch(1)

    /**
     * Stands in for `callExecutor`: blocking, and not interruptible by coroutine cancellation.
     *
     * Not `private`. `FunctionScheduler` invokes the function through `KFunction.call`, and reflection
     * cannot reach a private member — it fails with `IllegalCallableAccessException` at run time, which
     * looks nothing like the visibility mistake it is.
     */
    fun blockingWork(marker: String): String {
        started.countDown()
        release.await(10, TimeUnit.SECONDS)
        return marker
    }

    private suspend fun startTheOnlyJob(scheduler: FunctionScheduler<String>) {
        // scheduleAndAwait registers the job before suspending, but from another coroutine, so poll
        // rather than assume it is there yet.
        withTimeout(5_000) {
            while (!scheduler.startNext()) delay(5)
        }
    }

    @Test
    fun `a started job whose awaiter is cancelled stays counted until it really finishes`() = runBlocking {
        val scheduler = FunctionScheduler(this@FunctionSchedulerTest::blockingWork)

        val waiter = launch(Dispatchers.IO) { scheduler.scheduleAndAwait("x") }
        startTheOnlyJob(scheduler)
        assertTrue(started.await(5, TimeUnit.SECONDS)) { "the blocking body should be running" }

        waiter.cancelAndJoin()

        assertEquals(1, scheduler.countActive()) {
            "The executor is still working on this submission, so it must still count against " +
                    "maxLoad. Dropping it here is how core admits more work than a grading host can " +
                    "take."
        }

        release.countDown()

        // And it must not be counted forever: once the call returns, the job leaves the queue on its
        // own, with no awaiter left to do it.
        withTimeout(5_000) {
            while (scheduler.size() > 0) delay(5)
        }
        assertEquals(0, scheduler.countActive())
    }

    /**
     * A job nobody started and nobody is waiting for any more must leave the queue.
     *
     * **This one passes against the unfixed code too, and saying so is the point.** The finding proposed
     * `if (!isCompleted) cancel()` in the `finally`, on the reasoning that an uncancelled job stays a
     * candidate for `startNext()` and would later spend a grading slot on a result nobody wants. It
     * would not: `startNext()` only ever looks at `jobs`, and the removal already took it out. So
     * cancelling an unstarted job has no observable effect through this class's own interface — it only
     * releases a lazy coroutine that would otherwise never complete, which is tidiness, not a defect.
     *
     * Kept as a control, because the fix moves this branch around and a version that leaked unstarted
     * jobs into the queue would show up here as a `startNext()` that still has something to start.
     */
    @Test
    fun `an unstarted job whose awaiter is cancelled leaves the queue`() = runBlocking {
        val scheduler = FunctionScheduler(this@FunctionSchedulerTest::blockingWork)

        val waiter = launch(Dispatchers.IO) { scheduler.scheduleAndAwait("x") }
        withTimeout(5_000) { while (scheduler.countWaiting() == 0) delay(5) }

        waiter.cancelAndJoin()

        assertEquals(0, scheduler.countWaiting()) { "it must no longer be offered to startNext()" }
        assertEquals(0, scheduler.size())
        // The latch is still at 1, i.e. `blockingWork` was never entered.
        assertEquals(1L, started.count) { "and it must never have run" }
    }

    /** The ordinary path: a job that completes normally is removed, and its result reaches the caller. */
    @Test
    fun `a job that completes normally leaves the queue`() = runBlocking {
        val scheduler = FunctionScheduler(this@FunctionSchedulerTest::blockingWork)
        release.countDown()

        val result = launch(Dispatchers.IO) {
            assertEquals("x", scheduler.scheduleAndAwait("x"))
        }
        startTheOnlyJob(scheduler)
        result.join()

        assertEquals(0, scheduler.size())
        assertEquals(0, scheduler.countActive())
    }
}
