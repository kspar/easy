package core.ems.service.snippet

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.FeedbackSnippet
import core.ems.service.AdocService
import mu.KotlinLogging
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Size


@RestController
@RequestMapping("/v2")
class TeacherCreateFeedbackSnippetController(val adocService: AdocService) {
    private val log = KotlinLogging.logger {}

    data class Req(
        @JsonProperty("snippet_adoc", required = true)
        @field:Size(max = 300000)
        @field:NotBlank
        val snippetAdoc: String
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/snippets")
    fun controller(@Valid @RequestBody dto: Req, caller: EasyUser) {

        log.info { "${caller.id} is creating new snippet: $dto" }

        insertSnippet(dto, caller.id)
    }

    private fun insertSnippet(dto: Req, teacherId: String) = transaction {
        FeedbackSnippet.insert {
            it[teacher] = teacherId
            it[snippetAdoc] = dto.snippetAdoc
            it[snippetHtml] = adocService.adocToHtml(dto.snippetAdoc)
            it[createdAt] = DateTime.now()
        }
        // Get the count of snippets for the given teacher
        val snippetCount = FeedbackSnippet.selectAll().where { FeedbackSnippet.teacher eq teacherId }.count().toInt()

        // If there are more than 100 snippets, delete the oldest ones
        val keep = 100
        if (snippetCount > keep) {
            val excessCount = snippetCount - keep

            // Select the IDs of the oldest snippets to delete
            val idsToDelete = FeedbackSnippet
                .select(FeedbackSnippet.id)
                .where { FeedbackSnippet.teacher eq teacherId }
                .orderBy(FeedbackSnippet.createdAt, SortOrder.ASC)
                .limit(excessCount)
                .map { it[FeedbackSnippet.id] }

            // Delete the oldest snippets
            FeedbackSnippet.deleteWhere { FeedbackSnippet.id inList idsToDelete }
        }
    }
}
