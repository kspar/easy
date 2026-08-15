package core.testing

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The guard that stops the test suite emptying a database somebody cares about.
 *
 * Worth its own tests because of what it protects and how it is reached: [truncateAll] deletes
 * every row in every table, and with `EASY_TEST_JDBC_URL` set the target is whatever a human typed.
 * The guard has existed since EZ-1717 and has never had a test — which is an odd place to have no
 * coverage, since the whole point of it is that the failure it prevents is unrecoverable.
 *
 * Context-free by construction: it parses a string. So this runs on every push rather than only
 * where a database exists.
 */
class DisposableDatabaseGuardTest {

    private fun rejects(url: String): String =
        assertThrows(IllegalStateException::class.java) { assertDisposableDatabase(url) }.message.orEmpty()

    @Test
    fun `accepts a local database whose name ends in _test`() {
        assertDisposableDatabase("jdbc:postgresql://localhost:5432/easyems_test")
        assertDisposableDatabase("jdbc:postgresql://127.0.0.1:5432/easyems_test")
        // The mapped port Testcontainers hands out is arbitrary, so the port must not matter.
        assertDisposableDatabase("jdbc:postgresql://localhost:49213/easyems_test")
        // Query parameters are not part of the database name.
        assertDisposableDatabase("jdbc:postgresql://localhost:5432/easyems_test?loggerLevel=OFF")
    }

    @Test
    fun `refuses the dev database, which is the mistake it exists to prevent`() {
        val message = rejects("jdbc:postgresql://localhost:5432/easyems")
        assertTrue("easyems" in message) { "The message should name the database it refused: $message" }
        assertTrue("_test" in message) { "The message should say what it wanted: $message" }
    }

    @Test
    fun `refuses a remote host even when the database name looks disposable`() {
        val message = rejects("jdbc:postgresql://dev.ems.lahendus.ut.ee:5432/easyems_test")
        assertTrue("dev.ems.lahendus.ut.ee" in message) { "The message should name the host: $message" }
    }

    @Test
    fun `refuses a name that merely contains _test rather than ending in it`() {
        rejects("jdbc:postgresql://localhost:5432/easyems_test_prod_copy")
    }

    /**
     * The trap documented in [TestDatabase]: Testcontainers also offers a `jdbc:tc:` URL, and
     * `URI("tc:postgresql:16:///easyems_test").host` is null. The guard must fail closed on it
     * rather than read a null host as "not remote, therefore fine".
     */
    @Test
    fun `refuses the jdbc tc scheme, whose host cannot be parsed`() {
        rejects("jdbc:tc:postgresql:16:///easyems_test")
    }

    @Test
    fun `refuses something that is not a URL at all`() {
        rejects("jdbc:not a url")
        rejects("")
    }
}
