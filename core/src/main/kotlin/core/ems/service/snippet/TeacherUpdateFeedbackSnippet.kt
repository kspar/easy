package core.ems.service.snippet

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.FeedbackSnippet
import core.ems.service.AdocService
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import mu.KotlinLogging
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import javax.validation.constraints.Size


@RestController
@RequestMapping("/v2")
class TeacherUpdateFeedbackSnippetController(val adocService: AdocService) {
    private val log = KotlinLogging.logger {}

    data class Req(@JsonProperty("snippet_adoc", required = false) @field:Size(max = 300000) val snippetAdoc: String?)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PutMapping("/snippets/{snippetId}")
    fun controller(@PathVariable("snippetId") snippetIdStr: String, @Valid @RequestBody dto: Req?, caller: EasyUser) {
        val snippetId = snippetIdStr.idToLongOrInvalidReq()

        log.info { "${caller.id} is updating snippet $snippetId: $dto" }

        updateOrDeleteSnippet(dto, snippetId, caller.id)
    }

    private fun updateOrDeleteSnippet(dto: Req?, snippetId: Long, teacherId: String) = transaction {

        val count = when (dto?.snippetAdoc) {
            null -> FeedbackSnippet.deleteWhere { FeedbackSnippet.id eq snippetId and (teacher eq teacherId) }
            else -> {
                FeedbackSnippet.update({ FeedbackSnippet.id eq snippetId and (FeedbackSnippet.teacher eq teacherId) }) {
                    it[snippetAdoc] = dto.snippetAdoc
                    it[snippetHtml] = adocService.adocToHtml(dto.snippetAdoc)
                    it[createdAt] = DateTime.now()
                }
            }
        }

        if (count == 0) {
            throw InvalidRequestException("No snippet with ID $snippetId found.")
        }
    }
}
