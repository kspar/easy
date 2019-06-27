// Top-level property to access strings
var Str: TranslatableStrings = EstStrings


abstract class TranslatableStrings {
    abstract val fetchingCoursesFailed: String
}


object EstStrings : TranslatableStrings() {
    override val fetchingCoursesFailed: String
        get() = "Kursuste laadimine ebaõnnestus."
}
