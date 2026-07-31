package core.ems.service.snippet

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.FeedbackSnippet
import core.util.DateTimeSerializer
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
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
        @get:JsonProperty("snippets")
        @get:JsonInclude(JsonInclude.Include.NON_NULL) val snippets: List<SnippetResp>
    )

    data class SnippetResp(
        @get:JsonProperty("id") val id: String,
        @get:JsonProperty("snippet_md") val snippetMd: String,
        @get:JsonProperty("snippet_html") val snippetHtml: String,
        @get:JsonProperty("created_at") @get:JsonSerialize(using = DateTimeSerializer::class) val createdAt: DateTime
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/snippets")
    fun controller(caller: EasyUser): Resp {

        log.info { "Getting snippets for ${caller.id}" }

        return selectSnippets(caller.id)
    }

    private fun selectSnippets(teacherId: String): Resp = transaction {
        Resp(
            FeedbackSnippet
                .selectAll()
                .where { FeedbackSnippet.teacher eq teacherId }
                .orderBy(FeedbackSnippet.createdAt, SortOrder.DESC)
                .map {
                    SnippetResp(
                        it[FeedbackSnippet.id].value.toString(),
                        it[FeedbackSnippet.snippetMd],
                        it[FeedbackSnippet.snippetHtml],
                        it[FeedbackSnippet.createdAt],
                    )
                })
    }
}
