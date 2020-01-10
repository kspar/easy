package core.ems.service.file

import core.conf.security.EasyUser
import core.db.StoredFile
import core.db.StoredFile.data
import core.db.StoredFile.type
import mu.KotlinLogging
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.sql.Blob
import javax.servlet.http.HttpServletResponse

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class ReadStoredFileController {


    @GetMapping("/resource/{fileId}")
    fun controller(@PathVariable("fileId") fileIdString: String,
                   caller: EasyUser,
                   response: HttpServletResponse) {

        log.debug { "${caller.id} is querying file $fileIdString" }
        val storedFile = selectFile(fileIdString)

        if (storedFile != null) {
            response.contentType = storedFile.type
            response.outputStream.write(storedFile.blob.getBytes(1, storedFile.blob.length().toInt()))
            // Recommended to free.
            storedFile.blob.free()
        } else {
            response.status = HttpServletResponse.SC_NOT_FOUND
        }
    }
}

data class TempStoredFile(val type: String, val blob: Blob)

private fun selectFile(fileIdString: String): TempStoredFile? {
    return transaction {
        StoredFile.slice(type, data)
                .select { StoredFile.id eq fileIdString }
                .map { TempStoredFile(it[type], it[data]) }
                .firstOrNull()
    }
}
