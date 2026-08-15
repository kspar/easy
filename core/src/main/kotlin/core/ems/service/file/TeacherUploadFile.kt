package core.ems.service.file

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.StoredFile
import core.ems.service.storage.StorageService
import core.ems.service.storage.contentDispositionFor
import core.ems.service.storage.newStorageKey
import core.exception.InvalidRequestException
import core.exception.ReqError
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.tika.Tika
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.joda.time.DateTime
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/**
 * Uploading a file — an image pasted into an exercise or an article, in practice.
 *
 * **`multipart/form-data`, not base64 in a JSON body.** The old shape cost 33% on the wire and held
 * the base64 string, the decoded array and the JDBC copy in heap simultaneously, roughly four times
 * the file. Spring spools a multipart request to a temp file past
 * `spring.servlet.multipart.file-size-threshold`, so nothing here is proportional to the file size
 * in memory, which is what makes a large admin upload possible at all. Changing it cost nothing
 * because the SPA has never called this endpoint — there is no upload affordance in the app yet.
 *
 * The response is the storage key. That key is the row id, the object key, and the middle segment of
 * `/v2/resource/<key>/<filename>`, which is what a caller puts in Markdown.
 */
@RestController
@RequestMapping("/v2")
class UploadStoredFileController(private val storageService: StorageService) {
    private val log = KotlinLogging.logger {}

    /** 20 MB. A teacher is uploading a screenshot or a diagram. */
    @Value("\${easy.core.upload.max-bytes.teacher}")
    private var maxBytesTeacher: Long = 0

    /** 1 GB. Admins occasionally have a reason to put something large somewhere linkable. */
    @Value("\${easy.core.upload.max-bytes.admin}")
    private var maxBytesAdmin: Long = 0

    private val tika = Tika()

    /**
     * The id alone is not enough for a caller to build the URL it just created.
     *
     * [filename] is the *sanitised* one, which may differ from what was sent — path separators,
     * quotes and control characters are stripped — and it is the last segment of
     * `/v2/resource/<id>/<filename>`. [mimeType] is what Tika sniffed from the content, which is
     * how an editor decides between `![alt](url)` and `[name](url)` without trusting the extension.
     * A client guessing either would be wrong in exactly the cases that matter.
     */
    data class Resp(
        @get:JsonProperty("id") val id: String,
        @get:JsonProperty("filename") val filename: String,
        @get:JsonProperty("mime_type") val mimeType: String,
    )

    @Secured("ROLE_ADMIN", "ROLE_TEACHER")
    @PostMapping("/files")
    fun controller(@RequestParam("file") file: MultipartFile, caller: EasyUser): Resp {

        log.info { "${caller.id} is uploading '${file.originalFilename}' (${file.size} bytes)" }

        // The ceiling is per role, but spring.servlet.multipart.max-file-size is global and has to
        // be the larger of the two — so a teacher's oversized upload is spooled to disk before it is
        // rejected here. nginx's client_max_body_size is the outer bound. Rejecting it earlier would
        // mean a Content-Length check in a filter ahead of multipart resolution, which is not worth
        // the machinery for an endpoint only staff can reach.
        val maxBytes = if (caller.isAdmin()) maxBytesAdmin else maxBytesTeacher
        if (file.size > maxBytes) {
            throw InvalidRequestException(
                "File is ${file.size} bytes, over the ${maxBytes}-byte limit for this account.",
                ReqError.INVALID_PARAMETER_VALUE, notify = false
            )
        }
        if (file.isEmpty) {
            throw InvalidRequestException("File is empty.", ReqError.INVALID_PARAMETER_VALUE, notify = false)
        }

        val filename = sanitiseFilename(file.originalFilename)

        // Sniffed, never taken from the client. This value becomes the Content-Type of a publicly
        // readable object, so a client-supplied one would be a header we serve to the internet on
        // somebody else's say-so.
        val mimeType = file.inputStream.use { tika.detect(it, filename) }

        val key = newStorageKey()
        storageService.put(key, file.inputStream, file.size, mimeType, contentDispositionFor(mimeType, filename))

        // After the object, deliberately. A row with no object is a broken image; an object with no
        // row is invisible junk that the sweep collects on its next run. The second is the better
        // failure.
        transaction {
            StoredFile.insert {
                it[id] = key
                it[StoredFile.mimeType] = mimeType
                it[StoredFile.filename] = filename
                it[sizeBytes] = file.size
                it[createdAt] = DateTime.now()
                it[owner] = caller.id
                it[persistent] = false
            }
        }

        return Resp(key, filename, mimeType)
    }
}

/**
 * Strip a client-supplied filename down to something safe to put in a `Content-Disposition` header
 * and in a URL.
 *
 * The old code interpolated it raw, so a quote or a CRLF in the name reached the response header —
 * a header-injection primitive handed to any teacher. Path separators go too, because the name is
 * the last segment of the URL a file is served from and there is no reason for it to have depth.
 */
fun sanitiseFilename(raw: String?): String {
    val name = raw.orEmpty()
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .filter { it.code >= 0x20 && it != '"' }
        .trim()
        .take(255)
    return name.ifBlank { "file" }
}
