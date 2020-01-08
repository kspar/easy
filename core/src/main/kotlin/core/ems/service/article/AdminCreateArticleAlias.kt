package core.ems.service.article

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Admin
import core.db.Article
import core.db.ArticleAlias
import core.db.insertOrUpdate
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import mu.KotlinLogging
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Pattern
import javax.validation.constraints.Size

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class CreateArticleAliasController {

    data class Req(@JsonProperty("alias", required = true)
                   @field:NotBlank
                   @field:Size(max = 100)
                   @field:Pattern(regexp = "(\\w)+")
                   val alias: String)

    @Secured("ROLE_ADMIN")
    @PostMapping("/articles/{articleId}/aliases")
    fun controller(@Valid @RequestBody req: Req, @PathVariable("articleId") articleIdString: String, caller: EasyUser) {


        log.debug { "${caller.id} is creating alias for the article $articleIdString" }
        val articleId = articleIdString.idToLongOrInvalidReq()

        if (!articleExists(articleId)) {
            throw InvalidRequestException("No article with id $articleId found")
        }

        insertAlias(caller.id, articleId, req.alias)
    }
}


private fun insertAlias(createdBy: String, articleId: Long, alias: String) {
    val admin = EntityID(createdBy, Admin)

    transaction {
        ArticleAlias.insertOrUpdate(listOf(ArticleAlias.id), listOf(ArticleAlias.owner, ArticleAlias.createdAt)) {
            it[id] = EntityID(alias, ArticleAlias)
            it[owner] = admin
            it[article] = EntityID(articleId, Article)
            it[createdAt] = DateTime.now()
        }
    }
}

private fun articleExists(articleId: Long): Boolean {
    return transaction {
        Article.select { Article.id eq articleId }.count() == 1
    }
}


