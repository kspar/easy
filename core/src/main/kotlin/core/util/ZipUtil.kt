package core.util

import org.joda.time.DateTime
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream


data class Zip(val content: String, val name: String, val modifiedAt: DateTime)

fun writeZipFile(files: List<Zip>, outputStream: OutputStream): Unit {
    ZipOutputStream(outputStream).use { zipOut ->
        for (file in files) {
            zipOut.putNextEntry(zipEntry(file.name, file.modifiedAt))
            zipOut.write(file.content.toByteArray(Charsets.UTF_8))
        }
    }
}

private val TALLINN: ZoneId = ZoneId.of("Europe/Tallinn")
private const val EXTENDED_TIMESTAMP_TAG: Short = 0x5455

/**
 * A zip entry stamped with [modifiedAt] in both of the places unzippers look.
 *
 * The DOS date/time field has no zone and is read as the reader's local wall clock; Windows Explorer
 * reads only this. The server runs in UTC, so [ZipEntry.setLastModifiedTime] would write UTC wall
 * clock there and every file would show up hours early in Estonia. It is written as Tallinn time instead.
 *
 * The extended timestamp extra field (0x5455) holds the actual UTC instant, and macOS and Info-ZIP
 * prefer it when present. [ZipEntry.setTimeLocal] clears it, and [ZipEntry.setExtra] parses a
 * 0x5455 block back into it without touching the DOS field, so it is set second.
 */
fun zipEntry(name: String, modifiedAt: DateTime): ZipEntry {
    val instant = Instant.ofEpochMilli(modifiedAt.millis)
    return ZipEntry(name).apply {
        setTimeLocal(LocalDateTime.ofInstant(instant, TALLINN))
        extra = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(EXTENDED_TIMESTAMP_TAG)
            .putShort(5)
            .put(1.toByte()) // flags: modification time present
            .putInt(instant.epochSecond.toInt())
            .array()
    }
}
