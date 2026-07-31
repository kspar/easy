package core.conf.security

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Guards the check that stops an auth-disabled core from listening beyond loopback.
 *
 * Context-free by design, same as [EasyUserJwtConverterTest] — see the note there.
 */
class LoopbackAddressTest {

    @Test
    fun `accepts loopback addresses`() {
        assertTrue(isLoopbackAddress("127.0.0.1"))
        assertTrue(isLoopbackAddress("localhost"))
        assertTrue(isLoopbackAddress("::1"))
        // Anything in 127/8 is loopback, not just .0.1
        assertTrue(isLoopbackAddress("127.1.2.3"))
    }

    @Test
    fun `rejects addresses that bind beyond loopback`() {
        assertFalse(isLoopbackAddress("0.0.0.0"))
        assertFalse(isLoopbackAddress("::"))
        assertFalse(isLoopbackAddress("192.168.1.10"))
    }

    @Test
    fun `rejects a blank address, because unset means all interfaces`() {
        assertFalse(isLoopbackAddress(""))
        assertFalse(isLoopbackAddress("   "))
    }

    @Test
    fun `rejects an address it cannot resolve, rather than assuming the best`() {
        assertFalse(isLoopbackAddress("no-such-host.invalid"))
    }
}
