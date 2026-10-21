package core.ems.service.code

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.SimilarityProviderLanguage
import core.db.SimilarityProviderSetting
import core.ems.service.code.similarity.SimilarityLanguage
import core.ems.service.code.similarity.SimilarityService
import core.exception.InvalidRequestException
import core.exception.ReqError
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * An admin's settings for one installed similarity provider: whether it runs, where it stands in the
 * preference order, and which of its languages it may compare. The languages given replace the
 * provider's earlier overrides; one not given goes back to the provider's default - on if stable,
 * off if experimental.
 *
 * Providers are code, so there is nothing to create or delete: an id that is not installed is refused.
 */
@RestController
@RequestMapping("/v2")
class AdminUpdateSimilarityProviderController(private val similarityService: SimilarityService) {
    private val log = KotlinLogging.logger {}

    data class Req(
        @param:JsonProperty("enabled", required = true) val enabled: Boolean,
        @param:JsonProperty("priority", required = true) @field:Min(0) @field:Max(1000) val priority: Int,
        @param:JsonProperty("languages", required = true) @field:Valid @field:Size(max = 50) val languages: List<ReqLanguage>,
    )

    data class ReqLanguage(
        @param:JsonProperty("language", required = true) @field:NotBlank @field:Size(max = 20) val language: String,
        @param:JsonProperty("enabled", required = true) val enabled: Boolean,
    )

    @Secured("ROLE_ADMIN")
    @PutMapping("/admin/similarity-providers/{providerId}")
    fun controller(@PathVariable("providerId") providerId: String, @Valid @RequestBody body: Req, caller: EasyUser) {
        log.info { "Update similarity provider '${providerId.take(50)}' by ${caller.id}" }
        val provider = similarityService.registry.provider(providerId) ?: throw InvalidRequestException(
            "No similarity provider '${providerId.take(50)}' is installed.",
            ReqError.ENTITY_WITH_ID_NOT_FOUND, "id" to providerId.take(50), notify = false,
        )
        val languages = body.languages.map { req ->
            val language = SimilarityLanguage.byWireName(req.language)?.takeIf { it in provider.languages }
                ?: throw InvalidRequestException(
                    "Provider '${provider.id}' does not compare '${req.language}'.",
                    ReqError.INVALID_PARAMETER_VALUE, "language" to req.language, notify = false,
                )
            language to req.enabled
        }.toMap()

        transaction {
            SimilarityProviderSetting.upsert {
                it[SimilarityProviderSetting.provider] = provider.id
                it[enabled] = body.enabled
                it[priority] = body.priority
            }
            SimilarityProviderLanguage.deleteWhere { SimilarityProviderLanguage.provider eq provider.id }
            SimilarityProviderLanguage.batchInsert(languages.entries) { (language, enabled) ->
                this[SimilarityProviderLanguage.provider] = provider.id
                this[SimilarityProviderLanguage.language] = language.wireName
                this[SimilarityProviderLanguage.enabled] = enabled
            }
        }
    }
}
