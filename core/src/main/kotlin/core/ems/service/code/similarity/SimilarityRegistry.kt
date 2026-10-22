package core.ems.service.code.similarity

import core.db.SimilarityLanguageSetting
import core.db.SimilarityProviderLanguage
import core.db.SimilarityProviderSetting
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * The installed providers, and which of them compares a given language.
 *
 * What a provider can do is code - its languages and how ready each is, its default place in the
 * preference order. What it may do is an admin's, stored as overrides ([RegistrySettings]): a provider
 * switched off or moved up, one of its languages switched on or off, a language switched off for
 * similarity as a whole. With no overrides at all, every provider runs as declared - which is also
 * what a test sees, since tests start from empty tables.
 */
internal class SimilarityRegistry(val providers: List<SimilarityProvider>) {

    fun provider(id: String): SimilarityProvider? = providers.firstOrNull { it.id == id }

    /** The overrides as they stand, read in one transaction. */
    fun settings(): RegistrySettings = transaction {
        RegistrySettings(
            providers = SimilarityProviderSetting.selectAll().associate {
                it[SimilarityProviderSetting.provider] to ProviderOverride(it[SimilarityProviderSetting.enabled], it[SimilarityProviderSetting.priority])
            },
            providerLanguages = SimilarityProviderLanguage.selectAll().mapNotNull { row ->
                SimilarityLanguage.byWireName(row[SimilarityProviderLanguage.language])?.let {
                    (row[SimilarityProviderLanguage.provider] to it) to row[SimilarityProviderLanguage.enabled]
                }
            }.toMap(),
            languages = SimilarityLanguageSetting.selectAll().mapNotNull { row ->
                SimilarityLanguage.byWireName(row[SimilarityLanguageSetting.language])?.let { it to row[SimilarityLanguageSetting.enabled] }
            }.toMap(),
        )
    }

    fun bind(language: SimilarityLanguage, pinned: String? = null): SimilarityBinding = bind(language, settings(), pinned)

    /**
     * The provider that compares [language]: the first, in preference order, that is on and has the
     * language on - or failing every one, the first that has text on, comparing the cohort word by word
     * and saying so. A language an admin has switched off is refused outright rather than compared as
     * text: off means the admin does not want it compared, not that it should be compared worse.
     *
     * A provider [pinned] by a course comes first when it may compare the language, and then when it may
     * compare text. When it may not - switched off since, or not offered the language - the choice falls
     * to the preference order as if nothing were pinned, and the binding says the pin did not hold.
     */
    fun bind(language: SimilarityLanguage, settings: RegistrySettings, pinned: String? = null): SimilarityBinding {
        if (!settings.languageEnabled(language)) throw SimilarityUnavailable(language)
        val usable = preferenceOrder(settings).filter { settings.enabled(it) }
        if (pinned != null) {
            usable.firstOrNull { it.id == pinned && settings.mayCompare(it, language) }?.let { return SimilarityBinding(it.id, language, textAllowed = settings.mayCompareText(it)) }
        }
        val pinFailed = if (pinned != null) FALLBACK_PINNED_UNAVAILABLE else null
        usable.firstOrNull { settings.mayCompare(it, language) }?.let { return SimilarityBinding(it.id, language, pinFailed, textAllowed = settings.mayCompareText(it)) }
        if (language != SimilarityLanguage.TEXT && settings.languageEnabled(SimilarityLanguage.TEXT)) {
            // The pin holds for text too, where it may compare text; passed over, the binding says so, as
            // above - the language note alone hid from the teacher that their choice was not the one used.
            val text = usable.filter { settings.mayCompare(it, SimilarityLanguage.TEXT) }
            (text.firstOrNull { it.id == pinned } ?: text.firstOrNull())?.let {
                val fallback = if (pinned != null && it.id != pinned) FALLBACK_PINNED_UNAVAILABLE else FALLBACK_LANGUAGE_UNAVAILABLE
                return SimilarityBinding(it.id, SimilarityLanguage.TEXT, fallback = fallback)
            }
        }
        throw SimilarityUnavailable(language)
    }

    /** Highest priority first; equal priorities by id, so the order never depends on the list's. */
    fun preferenceOrder(settings: RegistrySettings): List<SimilarityProvider> =
        providers.sortedWith(compareByDescending<SimilarityProvider> { settings.priority(it) }.thenBy { it.id })

    companion object {
        /** Why a cohort was compared as text: no provider that may run takes its language. */
        const val FALLBACK_LANGUAGE_UNAVAILABLE = "LANGUAGE_UNAVAILABLE"

        /** Why a course's pinned provider did not compare it: it may not compare that language, or not at all. */
        const val FALLBACK_PINNED_UNAVAILABLE = "PINNED_UNAVAILABLE"
    }
}

internal data class ProviderOverride(val enabled: Boolean, val priority: Int)

/** An admin's overrides, and what each provider and language comes to with them. */
internal class RegistrySettings(
    val providers: Map<String, ProviderOverride> = emptyMap(),
    val providerLanguages: Map<Pair<String, SimilarityLanguage>, Boolean> = emptyMap(),
    val languages: Map<SimilarityLanguage, Boolean> = emptyMap(),
) {
    fun enabled(provider: SimilarityProvider): Boolean = providers[provider.id]?.enabled ?: true

    fun priority(provider: SimilarityProvider): Int = providers[provider.id]?.priority ?: provider.defaultPriority

    /** On if overridden on; otherwise on exactly when the provider calls the language stable. */
    fun languageEnabled(provider: SimilarityProvider, language: SimilarityLanguage): Boolean {
        val support = provider.languages[language] ?: return false
        return providerLanguages[provider.id to language] ?: (support == LanguageSupport.STABLE)
    }

    fun languageEnabled(language: SimilarityLanguage): Boolean = languages[language] ?: true

    /**
     * Whether [provider] may compare [language] here: the language is on for it, and where it would
     * compare it as something else in this JVM - JPlag compares a language whose frontend cannot load
     * as text - that is on too, for it and as a whole. Checking only the language asked for let a
     * provider compare word by word on a server where an admin had switched text off.
     */
    fun mayCompare(provider: SimilarityProvider, language: SimilarityLanguage): Boolean {
        if (!languageEnabled(provider, language)) return false
        val actual = provider.comparesAs(language)
        return actual == language || (languageEnabled(actual) && languageEnabled(provider, actual))
    }

    /** Whether [provider] may compare text here: on for it and as a whole. See [SimilarityBinding.textAllowed]. */
    fun mayCompareText(provider: SimilarityProvider): Boolean =
        languageEnabled(SimilarityLanguage.TEXT) && mayCompare(provider, SimilarityLanguage.TEXT)
}
