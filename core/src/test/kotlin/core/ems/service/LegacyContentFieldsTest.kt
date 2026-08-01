package core.ems.service

import core.exception.InvalidRequestException
import core.exception.ReqError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [rejectLegacyContentFields]. Context-free, so these run in CI — see the note in
 * `core/conf/security/EasyUserJwtConverterTest`.
 */
class LegacyContentFieldsTest {

    @Test
    fun `passes when no legacy field was sent`() {
        // The overwhelmingly common case: every modern client hits this path on every request.
        rejectLegacyContentFields("text_md", "text_adoc" to null, "text_html" to null)
    }

    @Test
    fun `rejects a single legacy field and names it`() {
        val e = assertThrows(InvalidRequestException::class.java) {
            rejectLegacyContentFields("text_md", "text_adoc" to "= Heading", "text_html" to null)
        }
        assertTrue(e.message.contains("text_adoc"), e.message)
        assertTrue(e.message.contains("text_md"), "should name the replacement: ${e.message}")
        assertEquals(ReqError.INVALID_PARAMETER_VALUE, e.code)
    }

    @Test
    fun `names every legacy field sent, not just the first`() {
        val e = assertThrows(InvalidRequestException::class.java) {
            rejectLegacyContentFields("text_md", "text_adoc" to "a", "text_html" to "<p>b</p>")
        }
        assertTrue(e.message.contains("text_adoc"), e.message)
        assertTrue(e.message.contains("text_html"), e.message)
    }

    @Test
    fun `reports the field names as attributes, for a client that parses rather than reads`() {
        val e = assertThrows(InvalidRequestException::class.java) {
            rejectLegacyContentFields("instructions_md", "instructions_adoc" to "x")
        }
        assertEquals(
            listOf("fields" to "instructions_adoc", "replacement" to "instructions_md"),
            e.attributes.toList(),
        )
    }

    @Test
    fun `joins multiple fields into one attribute rather than repeating the key`() {
        // attrs is serialised as a map, so one entry per field would keep only the last — the
        // response would name a single offending field when two were sent.
        val e = assertThrows(InvalidRequestException::class.java) {
            rejectLegacyContentFields("text_md", "text_adoc" to "a", "text_html" to "b")
        }
        assertEquals("text_adoc,text_html", e.attributes.toMap()["fields"])
        assertEquals(1, e.attributes.count { it.first == "fields" })
    }

    @Test
    fun `an empty string is a sent value, not an absent one`() {
        // "" round-trips through JSON as a present field. Treating it as absent would let a
        // client blank content through a field that is supposed to be gone.
        assertThrows(InvalidRequestException::class.java) {
            rejectLegacyContentFields("text_md", "text_adoc" to "")
        }
    }

    @Test
    fun `carries the replacement field name for each content type`() {
        val instructions = assertThrows(InvalidRequestException::class.java) {
            rejectLegacyContentFields("instructions_md", "instructions_adoc" to "x")
        }
        assertTrue(instructions.message.contains("instructions_md"), instructions.message)
    }
}
