package core.ems.service.snippet

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.FeedbackSnippet
import core.ems.service.MarkdownService
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*


@RestController
@RequestMapping("/v2")
class TeacherUpdateFeedbackSnippetController(val markdownService: MarkdownService) {
    private val log = KotlinLogging.logger {}

    data class Req(@param:JsonProperty("snippet_md", required = false) @field:Size(max = 300000) val snippetMd: String?)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PutMapping("/snippets/{snippetId}")
    fun controller(@PathVariable("snippetId") snippetIdStr: String, @Valid @RequestBody dto: Req?, caller: EasyUser) {
        val snippetId = snippetIdStr.idToLongOrInvalidReq()

        log.info { "${caller.id} is updating snippet $snippetId: $dto" }

        updateOrDeleteSnippet(dto, snippetId, caller.id)
    }

    private fun updateOrDeleteSnippet(dto: Req?, snippetId: Long, teacherId: String) = transaction {

        val count = when (dto?.snippetMd) {
            null -> FeedbackSnippet.deleteWhere { FeedbackSnippet.id eq snippetId and (teacher eq teacherId) }
            else -> {
                FeedbackSnippet.update({ FeedbackSnippet.id eq snippetId and (FeedbackSnippet.teacher eq teacherId) }) {
                    it[snippetMd] = dto.snippetMd
                    it[snippetHtml] = markdownService.mdToHtml(dto.snippetMd)
                    it[createdAt] = DateTime.now()
                }
            }
        }

        if (count == 0) {
            throw InvalidRequestException("No snippet with ID $snippetId found.")
        }
    }
}
