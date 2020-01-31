package core.util

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream


data class Zip(val content: String, val name: String)

fun writeZipFile(files: List<Zip>, outputStream: OutputStream): Unit {
    ZipOutputStream(outputStream).use { zipOut ->
        for (file in files) {
            zipOut.putNextEntry(ZipEntry(file.name))
            zipOut.write(file.content.toByteArray(Charsets.UTF_8))
        }
    }
}