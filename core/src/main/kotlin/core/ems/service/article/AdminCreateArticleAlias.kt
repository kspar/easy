package core.ems.service.article

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Admin
import core.db.Article
import core.db.ArticleAlias
import core.ems.service.assertArticleExists
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import mu.KotlinLogging
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.insert
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

        assertArticleExists(articleId)

        insertAlias(caller.id, articleId, req.alias)
    }
}


private fun insertAlias(createdBy: String, articleId: Long, alias: String) {
    val admin = EntityID(createdBy, Admin)

    transaction {
        if (ArticleAlias.select { ArticleAlias.id eq alias }.count() != 0) {
            throw InvalidRequestException("Article alias '$alias' is already in use.")
        }

        ArticleAlias.insert {
            it[id] = EntityID(alias, ArticleAlias)
            it[owner] = admin
            it[article] = EntityID(articleId, Article)
            it[createdAt] = DateTime.now()
        }
    }
}

