package core.ems.service.file

import core.conf.security.EasyUser
import core.db.StoredFile
import core.db.StoredFile.data
import core.db.StoredFile.filename
import core.db.StoredFile.type
import jakarta.servlet.http.HttpServletResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v2")
class ReadStoredFileController {
    private val log = KotlinLogging.logger {}


    @Secured("ROLE_ADMIN", "ROLE_TEACHER", "ROLE_STUDENT")
    @GetMapping("/resource/{fileId}")
    fun controller(@PathVariable("fileId") fileIdString: String, caller: EasyUser, response: HttpServletResponse) {

        log.info { "${caller.id} is querying file $fileIdString" }
        val storedFile = selectFile(fileIdString)

        if (storedFile != null) {
            response.contentType = storedFile.type
            response.setHeader("Content-disposition", """inline; filename="${storedFile.name}"""");
            response.outputStream.write(storedFile.blob)
        } else {
            response.status = HttpServletResponse.SC_NOT_FOUND
        }
    }

    data class TempStoredFile(val type: String, val name: String, val blob: ByteArray)

    private fun selectFile(fileIdString: String): TempStoredFile? = transaction {
        StoredFile.select(type, data, filename)
            .where { StoredFile.id eq fileIdString }
            .map { TempStoredFile(it[type], it[filename], it[data]) }
            .firstOrNull()
    }
}


