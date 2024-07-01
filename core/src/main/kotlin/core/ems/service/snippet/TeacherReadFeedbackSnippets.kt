package core.ems.service.snippet

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.FeedbackSnippet
import core.util.DateTimeSerializer
import mu.KotlinLogging
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v2")
class TeacherReadFeedbackSnippetsController {
    private val log = KotlinLogging.logger {}

    data class Resp(
        @JsonProperty("snippets")
        @JsonInclude(JsonInclude.Include.NON_NULL) val snippets: List<SnippetResp>
    )

    data class SnippetResp(
        @JsonProperty("id") val id: String,
        @JsonProperty("snippet_adoc") val snippetAdoc: String,
        @JsonProperty("snippet_html") val snippetHtml: String,
        @JsonProperty("created_at") @JsonSerialize(using = DateTimeSerializer::class) val createdAt: DateTime
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/snippets")
    fun controller(caller: EasyUser): Resp {

        log.info { "Getting snippets for ${caller.id}" }

        return selectSnippets(caller.id)
    }

    private fun selectSnippets(teacherId: String): Resp = transaction {
        Resp(FeedbackSnippet
            .selectAll()
            .where { FeedbackSnippet.teacher eq teacherId }
            .orderBy(FeedbackSnippet.createdAt, SortOrder.DESC)
            .map {
                SnippetResp(
                    it[FeedbackSnippet.id].value.toString(),
                    it[FeedbackSnippet.snippetAdoc],
                    it[FeedbackSnippet.snippetHtml],
                    it[FeedbackSnippet.createdAt],
                )
            })
    }
}
