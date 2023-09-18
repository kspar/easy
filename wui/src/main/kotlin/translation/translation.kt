package translation

import Key
import LocalStore
import kotlinx.browser.window

// Top-level property to access strings
lateinit var Str: TranslatableStrings

val activeLanguage
    get() = when (Str) {
        is EstStrings -> Language.EST
        is EngStrings -> Language.ENG
    }

enum class Language(val localeId: String) {
    EST("et"), ENG("en")
}

fun updateLanguage() {
    // Do not use locale from idp because there's a bug in the current version which doesn't respect default locale
    // Ideally we would want to use idp locale here if we don't have one set locally yet
    val setLanguageId = LocalStore.get(Key.LANGUAGE)

    Str = when (setLanguageId) {
        "et" -> EstStrings
        "en" -> EngStrings
        else -> EstStrings
    }
}

fun changeLanguage() {
    val newLang = when (Str) {
        is EstStrings -> Language.ENG
        is EngStrings -> Language.EST
    }

    LocalStore.set(Key.LANGUAGE, newLang.localeId)
    window.location.reload()
}
