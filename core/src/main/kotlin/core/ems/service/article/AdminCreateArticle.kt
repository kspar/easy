package core.ems.service.article

import com.fasterxml.jackson.annotation.JsonProperty
import core.ems.service.cache.articleCache
import core.conf.security.EasyUser
import core.db.Admin
import core.db.Article
import core.db.ArticleVersion
import core.db.StoredFile
import core.ems.service.AdocService
import core.ems.service.cache.CachingService
import mu.KotlinLogging
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Size

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class CreateArticleController(private val adocService: AdocService, private val cachingService: CachingService) {

    data class Req(
        @JsonProperty("title", required = true) @field:NotBlank @field:Size(max = 100) val title: String,
        @JsonProperty("text_adoc", required = false) @field:Size(max = 300000) val textAdoc: String?,
        @JsonProperty("public", required = true) val public: Boolean
    )

    data class Resp(@JsonProperty("id") val id: String)

    @Secured("ROLE_ADMIN")
    @PostMapping("/articles")
    fun controller(@Valid @RequestBody dto: Req, caller: EasyUser): Resp {

        log.debug { "Create article '${dto.title}' by ${caller.id}" }

        val html = dto.textAdoc?.let { adocService.adocToHtml(it) }

        val articleId = insertArticle(caller.id, dto, html).toString()
        cachingService.invalidate(articleCache)
        return Resp(articleId)
    }
}


private fun insertArticle(ownerId: String, req: CreateArticleController.Req, html: String?): Long {
    val adminId = EntityID(ownerId, Admin)

    return transaction {
        val time = DateTime.now()

        val articleId = Article.insertAndGetId {
            it[owner] = adminId
            it[public] = req.public
            it[createdAt] = time
        }

        ArticleVersion.insert {
            it[article] = articleId
            it[author] = adminId
            it[validFrom] = time
            it[title] = req.title
            it[textHtml] = html
            it[textAdoc] = req.textAdoc
        }

        if (html != null) {
            val inUse = StoredFile.slice(StoredFile.id)
                .select { StoredFile.usageConfirmed eq false }
                .map { it[StoredFile.id].value }
                .filter { html.contains(it) }

            StoredFile.update({ StoredFile.id inList inUse }) {
                it[StoredFile.usageConfirmed] = true
                it[StoredFile.article] = articleId
            }
        }
        articleId.value
    }
}

