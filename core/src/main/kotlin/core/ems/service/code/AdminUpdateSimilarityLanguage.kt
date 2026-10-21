package core.ems.service.code

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.SimilarityLanguageSetting
import core.ems.service.code.similarity.SimilarityLanguage
import core.exception.InvalidRequestException
import core.exception.ReqError
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.Valid
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Switches a language on or off for similarity as a whole, whatever any provider could do with it. Off is
 * a refusal - the check answers SIMILARITY_UNAVAILABLE - rather than a weaker comparison as text: an admin
 * who turns a language off does not want it compared.
 */
@RestController
@RequestMapping("/v2")
class AdminUpdateSimilarityLanguageController {
    private val log = KotlinLogging.logger {}

    data class Req(@param:JsonProperty("enabled", required = true) val enabled: Boolean)

    @Secured("ROLE_ADMIN")
    @PutMapping("/admin/similarity-languages/{language}")
    fun controller(@PathVariable("language") wireName: String, @Valid @RequestBody body: Req, caller: EasyUser) {
        val language = SimilarityLanguage.byWireName(wireName) ?: throw InvalidRequestException(
            "No similarity language '${wireName.take(20)}'.",
            ReqError.ENTITY_WITH_ID_NOT_FOUND, "language" to wireName.take(20), notify = false,
        )
        log.info { "Similarity for ${language.wireName} ${if (body.enabled) "on" else "off"} by ${caller.id}" }
        transaction {
            SimilarityLanguageSetting.upsert {
                it[SimilarityLanguageSetting.language] = language.wireName
                it[enabled] = body.enabled
            }
        }
    }
}
