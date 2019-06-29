// Top-level property to access strings
var Str: TranslatableStrings = EstStrings


abstract class TranslatableStrings {
    abstract val somethingWentWrong: String
    abstract val fetchingCoursesFailed: String
    abstract val myCourses: String
    abstract val accountData: String
    abstract val logOut: String
    abstract val roleChangeStudent: String
    abstract val roleChangeBack: String
    abstract val roleCHangeStudentSuffix: String
}


object EstStrings : TranslatableStrings() {
    override val somethingWentWrong: String
        get() = "Midagi läks valesti... Proovi lehte uuendada ja kui viga kordub, siis võta ühendust administraatoriga."
    override val fetchingCoursesFailed: String
        get() = "Kursuste laadimine ebaõnnestus."
    override val myCourses: String
        get() = "Minu kursused"
    override val accountData: String
        get() = "Konto andmed"
    override val logOut: String
        get() = "Logi välja"
    override val roleChangeStudent: String
        get() = "Digimuutu õpilaseks"
    override val roleChangeBack: String
        get() = "Taasmuutu põhirollile"
    override val roleCHangeStudentSuffix: String
        get() = " (õpilane)"
}
