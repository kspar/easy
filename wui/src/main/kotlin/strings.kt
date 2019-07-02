// Top-level property to access strings
var Str: TranslatableStrings = EstStrings


abstract class TranslatableStrings {

    // General
    abstract val noPermissionForPage: String
    abstract val somethingWentWrong: String

    // Navbar
    abstract val topMenuCourses: String
    abstract val accountData: String
    abstract val logOut: String
    abstract val roleChangeStudent: String
    abstract val roleChangeBack: String
    abstract val roleCHangeStudentSuffix: String

    // Sidenav
    abstract val sidenavHeader: String
    abstract val newExercise: String
    abstract val addExistingExercise: String
    abstract val participants: String
    abstract val grades: String

    // Courses page
    abstract val coursesTitle: String
    abstract val coursesTitleAdmin: String
    abstract val fetchingCoursesFailed: String
    abstract val newCourseLink: String
    abstract val coursesStudents: String
    abstract val coursesStudent: String

    // New course page
    abstract val courseCreationFailed: String
    abstract val newCourseName: String
    abstract val addNewCourse: String
}


private object EstStrings : TranslatableStrings() {
    override val topMenuCourses: String
        get() = "Minu kursused"
    override val noPermissionForPage: String
        get() = "Teil puudub õigus selle lehe vaatamiseks."
    override val newCourseLink: String
        get() = "Uus kursus"
    override val coursesStudents: String
        get() = "õpilast"
    override val coursesStudent: String
        get() = "õpilane"
    override val addNewCourse: String
        get() = "Lisa uus kursus"
    override val newCourseName: String
        get() = "Uue kursuse nimi"
    override val somethingWentWrong: String
        get() = "Midagi läks valesti... Proovi lehte uuendada ja kui viga kordub, siis võta ühendust administraatoriga."
    override val fetchingCoursesFailed: String
        get() = "Kursuste laadimine ebaõnnestus."
    override val courseCreationFailed: String
        get() = "Uue kursuse loomine ebaõnnestus."
    override val coursesTitleAdmin: String
        get() = "Kõik kursused"
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
    override val sidenavHeader: String
        get() = "Kursuse seaded"
    override val newExercise: String
        get() = "Uus ülesanne"
    override val addExistingExercise: String
        get() = "Lisa olemasolev ülesanne"
    override val participants: String
        get() = "Osalejad"
    override val grades: String
        get() = "Hinded"
    override val coursesTitle: String
        get() = "Minu kursused"
}
