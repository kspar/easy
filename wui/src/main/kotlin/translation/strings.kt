package translation

// Top-level property to access strings
var Str: TranslatableStrings = EstStrings

fun setStrings() {
    Str = when (LocalStore.get(Key.LANGUAGE)) {
        "et" -> EstStrings
        "en" -> EngStrings
        else -> EstStrings
    }
}
