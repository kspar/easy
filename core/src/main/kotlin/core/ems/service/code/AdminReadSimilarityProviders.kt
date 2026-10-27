package core.ems.service.code

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.CourseExerciseSimilaritySettings
import core.db.CourseSimilaritySettings
import core.ems.service.code.similarity.SimilarityLanguage
import core.ems.service.code.similarity.SimilarityService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The similarity providers installed, in the order a language is offered to them, and what an admin has
 * made of each: every value as it now applies, with whether it is the provider's own default or an
 * admin's override, so the page can say which is which.
 */
@RestController
@RequestMapping("/v2")
class AdminReadSimilarityProvidersController(private val similarityService: SimilarityService) {
    private val log = KotlinLogging.logger {}

    data class Resp(
        @get:JsonProperty("providers") val providers: List<RespProvider>,
        @get:JsonProperty("languages") val languages: List<RespLanguage>,
    )

    data class RespProvider(
        @get:JsonProperty("id") val id: String,
        @get:JsonProperty("enabled") val enabled: Boolean,
        @get:JsonProperty("priority") val priority: Int,
        @get:JsonProperty("default_priority") val defaultPriority: Int,
        @get:JsonProperty("overridden") val overridden: Boolean,
        @get:JsonProperty("languages") val languages: List<RespProviderLanguage>,
        /**
         * How many courses chose it for their checks, and how many course exercises for their own: what
         * switching it off moves to Automatic, which the page says before the admin does it.
         */
        @get:JsonProperty("pinned_courses") val pinnedCourses: Int,
        @get:JsonProperty("pinned_exercises") val pinnedExercises: Int,
    )

    data class RespProviderLanguage(
        @get:JsonProperty("language") val language: String,
        /** `stable`, on unless switched off, or `experimental`, off unless switched on. */
        @get:JsonProperty("support") val support: String,
        @get:JsonProperty("enabled") val enabled: Boolean,
        @get:JsonProperty("overridden") val overridden: Boolean,
    )

    data class RespLanguage(
        @get:JsonProperty("language") val language: String,
        @get:JsonProperty("enabled") val enabled: Boolean,
        /** The provider that would compare it now, and the language it would compare it as; null if none would. */
        @get:JsonProperty("provider") val provider: String?,
        @get:JsonProperty("compared_as") val comparedAs: String?,
    )

    @Secured("ROLE_ADMIN")
    @GetMapping("/admin/similarity-providers")
    fun controller(caller: EasyUser): Resp {
        log.info { "Read similarity providers by ${caller.id}" }
        val registry = similarityService.registry
        val settings = registry.settings()
        val (pinnedCourses, pinnedExercises) = pinned()
        return Resp(
            registry.preferenceOrder(settings).map { provider ->
                RespProvider(
                    provider.id,
                    settings.enabled(provider),
                    settings.priority(provider),
                    provider.defaultPriority,
                    provider.id in settings.providers,
                    provider.languages.map { (language, support) ->
                        RespProviderLanguage(
                            language.wireName,
                            support.wireName,
                            settings.languageEnabled(provider, language),
                            (provider.id to language) in settings.providerLanguages,
                        )
                    },
                    pinnedCourses[provider.id] ?: 0,
                    pinnedExercises[provider.id] ?: 0,
                )
            },
            SimilarityLanguage.entries.map { language ->
                val binding = runCatching { registry.bind(language, settings) }.getOrNull()
                RespLanguage(
                    language.wireName,
                    settings.languageEnabled(language),
                    binding?.provider,
                    // What the run compares it as, as the estimate and the run report it: JPlag without javac
                    // compares Java as text though Java is what it was bound to.
                    binding?.let { (registry.provider(it.provider)?.comparesAs(it.language) ?: it.language).wireName },
                )
            },
        )
    }

    /** Each provider's count of the courses that chose it, and of the course exercises that chose it for their own. */
    private fun pinned(): Pair<Map<String, Int>, Map<String, Int>> = transaction {
        val courses = CourseSimilaritySettings.select(CourseSimilaritySettings.provider)
            .where { CourseSimilaritySettings.provider.isNotNull() }
            .mapNotNull { it[CourseSimilaritySettings.provider] }
            .groupingBy { it }.eachCount()
        val exercises = CourseExerciseSimilaritySettings.select(CourseExerciseSimilaritySettings.provider)
            .where { (CourseExerciseSimilaritySettings.ownProvider eq true) and CourseExerciseSimilaritySettings.provider.isNotNull() }
            .mapNotNull { it[CourseExerciseSimilaritySettings.provider] }
            .groupingBy { it }.eachCount()
        courses to exercises
    }
}
