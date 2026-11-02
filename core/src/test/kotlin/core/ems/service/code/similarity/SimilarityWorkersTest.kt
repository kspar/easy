package core.ems.service.code.similarity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SimilarityWorkersTest {

    private fun workers(threads: Int) = SimilarityWorkers(threads, Deadline.after(TimeUnit.MINUTES.toNanos(1)))

    @Test
    fun `the first failure stops the work not yet started, and an Error is reported over a timeout`() {
        // One thread: once the first task fails, the ones queued behind it are not run.
        val ran = AtomicInteger()
        workers(1).use { workers ->
            val failed = assertThrows<IllegalStateException> {
                workers.all(listOf({ error("broken solution") }, { ran.incrementAndGet() }, { ran.incrementAndGet() }))
            }
            assertEquals("broken solution", failed.message)
        }
        assertEquals(0, ran.get())

        // Two tasks failing at once, the timeout first: the out-of-memory error is what the caller sees,
        // so it reaches the error handler instead of reading as "try a smaller group".
        val bothRunning = CountDownLatch(2)
        workers(2).use { workers ->
            val worst = assertThrows<OutOfMemoryError> {
                workers.all(
                    listOf(
                        { bothRunning.countDown(); bothRunning.await(); throw SimilarityTimedOut() },
                        { bothRunning.countDown(); bothRunning.await(); throw OutOfMemoryError("heap") },
                    ),
                )
            }
            assertTrue(worst.suppressed.single() is SimilarityTimedOut)
        }
    }
}
