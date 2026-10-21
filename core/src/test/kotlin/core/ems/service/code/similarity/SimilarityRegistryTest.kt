package core.ems.service.code.similarity

import core.ems.service.code.similarity.SimilarityLanguage.JAVA
import core.ems.service.code.similarity.SimilarityLanguage.PYTHON
import core.ems.service.code.similarity.SimilarityLanguage.SQL
import core.ems.service.code.similarity.SimilarityLanguage.TEXT
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Which provider compares a language, from the providers' own defaults and an admin's overrides. */
class SimilarityRegistryTest {

    private val jplag = JPlagProvider(maxSolutionTokens = 10_000, maxRepetition = 1_000_000)
    private val editDistance = EditDistanceProvider()
    private val registry = SimilarityRegistry(listOf(jplag, editDistance))

    private val jplagFirst = ProviderOverride(enabled = true, priority = 200)
    private val off = ProviderOverride(enabled = false, priority = 0)

    private fun bind(language: SimilarityLanguage, settings: RegistrySettings = RegistrySettings()) = registry.bind(language, settings)

    @Test
    fun `with nothing overridden every language is compared as before, by edit distance`() {
        // Deploying providers must change no course's scores until an admin chooses otherwise.
        for (language in SimilarityLanguage.entries) {
            assertEquals(SimilarityBinding(EditDistanceProvider.ID, language), bind(language), language.wireName)
        }
    }

    @Test
    fun `a provider moved up compares its stable languages, and its experimental ones only once switched on`() {
        val settings = RegistrySettings(providers = mapOf(JPlagProvider.ID to jplagFirst))
        for (language in listOf(PYTHON, JAVA, TEXT)) assertEquals(JPlagProvider.ID, bind(language, settings).provider, language.wireName)
        // SQL is experimental for JPlag: the next provider takes it until an admin vouches for it.
        assertEquals(SimilarityBinding(EditDistanceProvider.ID, SQL), bind(SQL, settings))
        val sqlOn = RegistrySettings(providers = mapOf(JPlagProvider.ID to jplagFirst), providerLanguages = mapOf((JPlagProvider.ID to SQL) to true))
        assertEquals(SimilarityBinding(JPlagProvider.ID, SQL), bind(SQL, sqlOn))
        // And a stable language can be switched off for one provider alone.
        val pythonOff = RegistrySettings(providers = mapOf(JPlagProvider.ID to jplagFirst), providerLanguages = mapOf((JPlagProvider.ID to PYTHON) to false))
        assertEquals(SimilarityBinding(EditDistanceProvider.ID, PYTHON), bind(PYTHON, pythonOff))
    }

    @Test
    fun `when no provider may take a language, the first that may take text compares it as text, and says so`() {
        val settings = RegistrySettings(providers = mapOf(EditDistanceProvider.ID to off))
        assertEquals(SimilarityBinding(JPlagProvider.ID, TEXT, SimilarityRegistry.FALLBACK_LANGUAGE_UNAVAILABLE), bind(SQL, settings))
    }

    @Test
    fun `a language switched off is refused, not compared as text`() {
        val sqlOff = RegistrySettings(languages = mapOf(SQL to false))
        assertThrows<SimilarityUnavailable> { bind(SQL, sqlOff) }
        assertEquals(EditDistanceProvider.ID, bind(PYTHON, sqlOff).provider)
        // With text off too, nothing is left to fall back on.
        val textOff = RegistrySettings(providers = mapOf(EditDistanceProvider.ID to off), languages = mapOf(TEXT to false))
        assertThrows<SimilarityUnavailable> { bind(SQL, textOff) }
    }

    @Test
    fun `with every provider off nothing is compared`() {
        val allOff = RegistrySettings(providers = mapOf(JPlagProvider.ID to off, EditDistanceProvider.ID to off))
        for (language in SimilarityLanguage.entries) assertThrows<SimilarityUnavailable> { bind(language, allOff) }
    }

    @Test
    fun `equal priorities are ordered by id, and an override for a provider not installed changes nothing`() {
        val tied = RegistrySettings(providers = mapOf(JPlagProvider.ID to ProviderOverride(true, 100), "gone" to jplagFirst))
        assertEquals(listOf(EditDistanceProvider.ID, JPlagProvider.ID), registry.preferenceOrder(tied).map { it.id })
        assertEquals(EditDistanceProvider.ID, bind(PYTHON, tied).provider)
    }
}
