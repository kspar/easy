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
    fun `a pinned provider comes first where it may run, and the order decides where it may not`() {
        // Pinned against the order: the course keeps its choice.
        assertEquals(SimilarityBinding(JPlagProvider.ID, PYTHON), registry.bind(PYTHON, RegistrySettings(), pinned = JPlagProvider.ID))
        // JPlag may not compare SQL by default (experimental), so the pin does not hold, and the check says so.
        assertEquals(
            SimilarityBinding(EditDistanceProvider.ID, SQL, SimilarityRegistry.FALLBACK_PINNED_UNAVAILABLE),
            registry.bind(SQL, RegistrySettings(), pinned = JPlagProvider.ID),
        )
        // Switched off by an admin since it was pinned: passed over the same way.
        val jplagOff = RegistrySettings(providers = mapOf(JPlagProvider.ID to off))
        assertEquals(SimilarityRegistry.FALLBACK_PINNED_UNAVAILABLE, registry.bind(PYTHON, jplagOff, pinned = JPlagProvider.ID).fallback)
        // A pin never overrides a language switched off as a whole.
        assertThrows<SimilarityUnavailable> { registry.bind(PYTHON, RegistrySettings(languages = mapOf(PYTHON to false)), pinned = JPlagProvider.ID) }
    }

    @Test
    fun `a pin holds when the language falls back to text, and a pin passed over there says so`() {
        // Python off for both providers, JPlag first: Python is compared as text, by the course's pin
        // where it may compare text, rather than by the first in the order.
        val pythonOff = RegistrySettings(
            providers = mapOf(JPlagProvider.ID to jplagFirst),
            providerLanguages = mapOf((JPlagProvider.ID to PYTHON) to false, (EditDistanceProvider.ID to PYTHON) to false),
        )
        assertEquals(SimilarityBinding(JPlagProvider.ID, TEXT, SimilarityRegistry.FALLBACK_LANGUAGE_UNAVAILABLE), registry.bind(PYTHON, pythonOff))
        assertEquals(
            SimilarityBinding(EditDistanceProvider.ID, TEXT, SimilarityRegistry.FALLBACK_LANGUAGE_UNAVAILABLE),
            registry.bind(PYTHON, pythonOff, pinned = EditDistanceProvider.ID),
        )
        // The pin may not compare text either: passed over, and the binding says so rather than only "as text".
        val pinTextOff = RegistrySettings(pythonOff.providers, pythonOff.providerLanguages + ((EditDistanceProvider.ID to TEXT) to false))
        assertEquals(
            SimilarityBinding(JPlagProvider.ID, TEXT, SimilarityRegistry.FALLBACK_PINNED_UNAVAILABLE),
            registry.bind(PYTHON, pinTextOff, pinned = EditDistanceProvider.ID),
        )
    }

    @Test
    fun `a provider that would compare a language as text here may do so only where text is on`() {
        // JPlag compares a language whose frontend cannot load in this JVM as text. Checking only the
        // language asked for let it compare word by word where an admin had switched text off.
        val noJava = JPlagProvider(maxSolutionTokens = 10_000, maxRepetition = 1_000_000).also { it.available = { language -> language != JAVA } }
        val registry = SimilarityRegistry(listOf(noJava, editDistance))
        val jplagFirst = mapOf(JPlagProvider.ID to this.jplagFirst)
        assertEquals(JPlagProvider.ID, registry.bind(JAVA, RegistrySettings(providers = jplagFirst)).provider)
        // Text off for JPlag, or as a whole: the next provider compares Java, as Java.
        val textOffForJplag = RegistrySettings(providers = jplagFirst, providerLanguages = mapOf((JPlagProvider.ID to TEXT) to false))
        assertEquals(SimilarityBinding(EditDistanceProvider.ID, JAVA), registry.bind(JAVA, textOffForJplag))
        assertEquals(false, registry.bind(PYTHON, textOffForJplag).textAllowed)
        val textOff = RegistrySettings(providers = jplagFirst, languages = mapOf(TEXT to false))
        assertEquals(SimilarityBinding(EditDistanceProvider.ID, JAVA, textAllowed = false), registry.bind(JAVA, textOff))
        // And a pin on it does not hold either.
        assertEquals(SimilarityRegistry.FALLBACK_PINNED_UNAVAILABLE, registry.bind(JAVA, textOff, pinned = JPlagProvider.ID).fallback)
    }

    @Test
    fun `equal priorities are ordered by id, and an override for a provider not installed changes nothing`() {
        val tied = RegistrySettings(providers = mapOf(JPlagProvider.ID to ProviderOverride(true, 100), "gone" to jplagFirst))
        assertEquals(listOf(EditDistanceProvider.ID, JPlagProvider.ID), registry.preferenceOrder(tied).map { it.id })
        assertEquals(EditDistanceProvider.ID, bind(PYTHON, tied).provider)
    }
}
