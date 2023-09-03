package translation

import Key
import LocalStore
import kotlinx.browser.window

// Top-level property to access strings
var Str: TranslatableStrings = EstStrings

enum class Language(val id: String) {
    EST("et"), ENG("en")
}

fun setStrings() {
    Str = when (LocalStore.get(Key.LANGUAGE)) {
        "et" -> EstStrings
        "en" -> EngStrings
        else -> EstStrings
    }
}

fun changeLanguage() {
    val newLang = when(Str) {
        is EstStrings -> Language.ENG
        is EngStrings -> Language.EST
    }

    LocalStore.set(Key.LANGUAGE, newLang.id)
    window.location.reload()
}
