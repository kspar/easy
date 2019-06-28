// Top-level property to access strings
var Str: TranslatableStrings = EstStrings


abstract class TranslatableStrings {
    abstract val fetchingCoursesFailed: String
    abstract val myCourses: String
    abstract val accountData: String
    abstract val logOut: String
}


object EstStrings : TranslatableStrings() {
    override val fetchingCoursesFailed: String
        get() = "Kursuste laadimine ebaõnnestus."
    override val myCourses: String
        get() = "Minu kursused"
    override val accountData: String
        get() = "Konto andmed"
    override val logOut: String
        get() = "Logi välja"
}
