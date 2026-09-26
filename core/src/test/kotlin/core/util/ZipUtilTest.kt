package core.util

import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TimeZone
import java.util.zip.ZipInputStream

/**
 * A downloaded submission carries the time it was submitted, readable both by tools that only know
 * the zone-less DOS field (Windows Explorer) and by those that prefer the extended timestamp.
 *
 * Reads the local file header's bytes directly: [ZipInputStream] reports the extended timestamp when
 * there is one, so it cannot tell whether the DOS field is right.
 *
 * Runs in UTC like the server (`EasyCoreApp` sets it in `main`, which tests never reach). In a
 * Tallinn JVM, naive UTC-based stamping would write Tallinn wall clock by accident and pass.
 */
class ZipUtilTest {
    private lateinit var originalZone: TimeZone

    @BeforeEach
    fun serverZone() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @AfterEach
    fun restoreZone() = TimeZone.setDefault(originalZone)

    @ParameterizedTest
    @CsvSource(
        "2026-09-24T05:42:10Z, 2026, 9, 24, 8, 42, 10",   // summer, UTC+3
        "2026-01-15T22:30:00Z, 2026, 1, 16, 0, 30, 0",    // winter, UTC+2, across midnight
    )
    fun `entry is stamped with the submission time`(
        submittedAt: String, year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int
    ) {
        val time = DateTime(submittedAt, DateTimeZone.UTC)
        val bytes = ByteArrayOutputStream().also {
            writeZipFile(listOf(Zip("print(1)", "a.py", time)), it)
        }.toByteArray()

        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val dosTime = header.getShort(10).toInt() and 0xFFFF
        val dosDate = header.getShort(12).toInt() and 0xFFFF
        assertEquals(
            listOf(year, month, day, hour, minute, second),
            listOf(
                (dosDate shr 9) + 1980, (dosDate shr 5) and 0xF, dosDate and 0x1F,
                dosTime shr 11, (dosTime shr 5) and 0x3F, (dosTime and 0x1F) * 2
            ),
            "DOS date/time should be Tallinn wall clock"
        )

        val entry = ZipInputStream(ByteArrayInputStream(bytes)).use { it.nextEntry!! }
        assertEquals(time.millis, entry.lastModifiedTime.toMillis(), "extended timestamp should be the UTC instant")
    }
}
