package translation

import Role
import pages.exercise_library.DirAccess

abstract class TranslatableStrings {

    // General

    abstract val notFoundPageTitle: String
    abstract val notFoundPageMsg: String
    abstract val noPermissionForPageTitle: String
    abstract val noPermissionForPageMsg: String
    abstract val noCourseAccessPageTitle: String
    abstract val noCourseAccessPageMsg: String
    abstract val somethingWentWrong: String
    abstract val yes: String
    abstract val no: String
    abstract val myCourses: String
    abstract val exerciseLibrary: String
    abstract val gradedAutomatically: String
    abstract val gradedByTeacher: String
    abstract val notGradedYet: String
    abstract val closeToggleLink: String
    abstract val doSave: String
    abstract val saving: String
    abstract val doAdd: String
    abstract val adding: String
    abstract val cancel: String
    abstract val solutionCodeTabName: String
    abstract val new: String
    abstract val exercise: String
    abstract val exercises: String
    abstract val doEditTitle: String
    abstract val doMove: String
    abstract val moving: String
    abstract val doDelete: String
    abstract val deleted: String
    abstract val doRestore: String
    abstract val doChange: String

    fun translateBoolean(bool: Boolean) = if (bool) yes else no
    fun translateRole(role: Role) = when (role) {
        Role.STUDENT -> roleStudent
        Role.TEACHER -> roleTeacher
        Role.ADMIN -> roleAdmin
    }

    fun translateStudents(count: Int) = if (count == 1) coursesStudent else coursesStudents
    fun translateExercises(count: Int) = if (count == 1) exercise else exercises
    fun translatePermission(permission: DirAccess) =
        when (permission) {
            DirAccess.P -> permissionP
            DirAccess.PR -> permissionPR
            DirAccess.PRA -> permissionPRA
            DirAccess.PRAW -> permissionPRAW
            DirAccess.PRAWM -> permissionPRAWM
        }

    abstract val permissionP: String
    abstract val permissionPR: String
    abstract val permissionPRA: String
    abstract val permissionPRAW: String
    abstract val permissionPRAWM: String


    // Navbar

    abstract val topMenuCourses: String
    abstract val activeRoleLabel: String
    abstract val roleAdmin: String
    abstract val roleTeacher: String
    abstract val roleStudent: String
    abstract val accountData: String
    abstract val logOut: String
    abstract val roleChangeStudentSuffix: String


    // Sidenav

    abstract val sidenavHeader: String
    abstract val newExercise: String
    abstract val addExistingExercise: String
    abstract val participants: String
    abstract val gradesLabel: String


    // Courses page

    abstract val noCoursesLabel: String
    abstract val coursesTitle: String
    abstract val coursesTitleAdmin: String
    abstract val fetchingCoursesFailed: String
    abstract val newCourseLink: String
    abstract val coursesStudents: String
    abstract val coursesStudent: String
    abstract val enrolledOnCourseAttrKey: String
    abstract val coursesSingular: String
    abstract val coursesPlural: String



    // New course page

    abstract val courseCreationFailed: String
    abstract val newCourseName: String
    abstract val addNewCourse: String


    // Exercises page

    abstract val deadlineLabel: String
    abstract val completedLabel: String
    abstract val startedLabel: String
    abstract val ungradedLabel: String
    abstract val unstartedLabel: String


    // Course exercise page

    abstract val tabExerciseLabel: String
    abstract val tabTestingLabel: String
    abstract val tabSubmissionsLabel: String
    abstract val tabSubmitLabel: String
    abstract val softDeadlineLabel: String
    abstract val hardDeadlineLabel: String
    abstract val graderTypeLabel: String
    abstract val thresholdLabel: String
    abstract val studentVisibleLabel: String
    abstract val studentVisibleFromTimeLabel: String
    abstract val assStudentVisibleLabel: String
    abstract val lastModifiedLabel: String
    abstract val graderTypeAuto: String
    abstract val graderTypeTeacher: String
    abstract val autoAssessmentLabel: String
    abstract val autoGradeLabel: String
    abstract val teacherAssessmentLabel: String
    abstract val gradeLabel: String
    abstract val doAutoAssess: String
    abstract val autoAssessing: String
    abstract val addAssessmentLink: String
    abstract val addAssessmentGradeLabel: String
    abstract val addAssessmentFeedbackLabel: String
    abstract val addAssessmentGradeValidErr: String
    abstract val addAssessmentButtonLabel: String
    abstract val assessmentAddedMsg: String
    abstract val submissionTimeLabel: String
    abstract val submitAndCheckLabel: String
    abstract val lastSubmTimeLabel: String
    abstract val submissionHeading: String
    abstract val latestSubmissionSuffix: String
    abstract val allSubmissionsLink: String
    abstract val loadingAllSubmissions: String
    abstract val oldSubmissionNote: String
    abstract val toLatestSubmissionLink: String
    abstract val aaTitle: String
    abstract val submitSuccessMsg: String


    // Exercise page

    abstract val usedOnCoursesLabel: String
    abstract val previewLabel: String
    abstract val exerciseSaved: String
    abstract val testType: String

}

object EstStrings : TranslatableStrings() {

    override val notFoundPageTitle = "Lehte ei leitud"
    override val notFoundPageMsg = "Siin pole midagi kasulikku näha. :("
    override val noPermissionForPageTitle = "Ligipääs puudub"
    override val noPermissionForPageMsg = "Sul puudub õigus selle lehe vaatamiseks. :("
    override val noCourseAccessPageTitle = "Ligipääs kursusele puudub"
    override val noCourseAccessPageMsg = "Sul puudub ligipääs sellele kursusele. :("





    override val solutionCodeTabName = "lahendus"
    override val activeRoleLabel = "Aktiivne roll:"
    override val roleAdmin = "Admin"
    override val roleTeacher = "Õpetaja"
    override val roleStudent = "Õpilane"
    override val usedOnCoursesLabel = "Kasutusel kursustel"
    override val previewLabel = "Eelvaade"
    override val doSave = "Salvesta"
    override val saving = "Salvestan..."
    override val doAdd = "Lisa"
    override val adding = "Lisan..."
    override val cancel = "Tühista"
    override val doEditTitle = "Muuda pealkirja"
    override val doMove = "Liiguta"
    override val moving = "Liigutan..."
    override val doDelete = "Kustuta"
    override val deleted = "Kustutatud"
    override val doRestore = "Taasta"
    override val doChange = "Muuda"
    override val exerciseSaved = "Ülesanne uuendatud"
    override val assessmentAddedMsg = "Hinnang lisatud"
    override val oldSubmissionNote = "See on vana esitus."
    override val toLatestSubmissionLink = "Vaata viimast esitust."
    override val loadingAllSubmissions = "Laen esitusi..."
    override val submissionHeading = "Esitus"
    override val latestSubmissionSuffix = "(viimane esitus)"
    override val allSubmissionsLink = "► Vaata kõiki esitusi"
    override val tabSubmitLabel = "Esitamine"
    override val submitAndCheckLabel = "Esita ja kontrolli"
    override val lastSubmTimeLabel = "Viimase esituse aeg"
    override val noCoursesLabel = "Sind ei ole veel ühelegi kursusele lisatud."
    override val softDeadlineLabel = "Tähtaeg"
    override val hardDeadlineLabel = "Sulgemise aeg"
    override val graderTypeLabel = "Hindamine"
    override val thresholdLabel = "Lävend"
    override val studentVisibleLabel = "Õpilastele nähtav"
    override val studentVisibleFromTimeLabel = "Muutub nähtavaks"
    override val assStudentVisibleLabel = "Hinnangud õpilastele nähtavad"
    override val lastModifiedLabel = "Viimati muudetud"
    override val submissionTimeLabel = "Esitamise aeg"
    override val yes = "jah"
    override val no = "ei"
    override val topMenuCourses = "Minu kursused"
    override val newCourseLink = "Uus kursus"
    override val coursesStudents = "õpilast"
    override val coursesStudent = "õpilane"
    override val enrolledOnCourseAttrKey = "Kursusel on"
    override val addNewCourse = "Lisa uus kursus"
    override val newCourseName = "Uue kursuse nimi"
    override val somethingWentWrong = "Midagi läks valesti... Proovi lehte uuendada ja kui viga kordub, siis võta ühendust administraatoriga."

    override val fetchingCoursesFailed = "Kursuste laadimine ebaõnnestus."
    override val courseCreationFailed = "Uue kursuse loomine ebaõnnestus."
    override val coursesTitleAdmin = "Kõik kursused"
    override val accountData = "Konto seaded"
    override val logOut = "Logi välja"
    override val roleChangeStudentSuffix = " (õpilane)"
    override val sidenavHeader = "Kursuse seaded"
    override val newExercise = "Uus ülesanne"
    override val addExistingExercise = "Lisa olemasolev ülesanne"
    override val participants = "Osalejad"
    override val gradesLabel = "Hinded"
    override val coursesTitle = "Minu kursused"
    override val myCourses = "Minu kursused"
    override val exerciseLibrary = "Ülesandekogu"
    override val deadlineLabel = "Tähtaeg"
    override val gradedAutomatically = "Automaatselt hinnatud"
    override val gradedByTeacher = "Õpetaja poolt hinnatud"
    override val notGradedYet = "Hindamata"
    override val completedLabel = "Lõpetanud"
    override val startedLabel = "Nässu läinud"
    override val ungradedLabel = "Hindamata"
    override val unstartedLabel = "Esitamata"
    override val tabExerciseLabel = "Ülesanne"
    override val tabTestingLabel = "Katsetamine"
    override val tabSubmissionsLabel = "Esitused"
    override val autoAssessmentLabel = "Automaatsed testid"
    override val autoGradeLabel = "Automaatne hinne"
    override val teacherAssessmentLabel = "Õpetaja kommentaar"
    override val gradeLabel = "Punktid"
    override val doAutoAssess = "Kontrolli"
    override val autoAssessing = "Kontrollin..."
    override val addAssessmentLink = "► Lisa hinnang"
    override val closeToggleLink = "▼ Sulge"
    override val graderTypeAuto = "automaatne"
    override val graderTypeTeacher = "manuaalne"
    override val addAssessmentGradeLabel = "Hinne (0-100)"
    override val addAssessmentFeedbackLabel = "Tagasiside"
    override val addAssessmentGradeValidErr = "Hinne peab olema arv 0 ja 100 vahel."
    override val addAssessmentButtonLabel = "Lisa hinnang"
    override val aaTitle = "Automaatkontroll"
    override val submitSuccessMsg = "Lahendus esitatud"

    override val new = "uus"
    override val exercise = "ülesanne"
    override val exercises = "ülesannet"
    override val permissionP = "Läbikäija"
    override val permissionPR = "Vaataja"
    override val permissionPRA = "Lisaja"
    override val permissionPRAW = "Muutja"
    override val permissionPRAWM = "Moderaator"
    override val testType = "Testi tüüp"
    override val coursesSingular = "kursus"
    override val coursesPlural = "kursust"
}