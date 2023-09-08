package translation

import Role
import pages.exercise_library.DirAccess

sealed class TranslatableStrings {

    // General
    abstract val otherLanguage: String
    abstract val notFoundPageTitle: String
    abstract val notFoundPageMsg: String
    abstract val noPermissionForPageMsg: String
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
    abstract val solutionEditorPlaceholder: String
    abstract val exerciseSingular: String
    abstract val exercisePlural: String
    abstract val doEditTitle: String
    abstract val doMove: String
    abstract val moving: String
    abstract val doDelete: String
    abstract val deleted: String
    abstract val doRestore: String
    abstract val doChange: String
    abstract val doAutoAssess: String
    abstract val autoAssessing: String
    abstract val tryAgain: String

    fun translateBoolean(bool: Boolean) = if (bool) yes else no
    fun translateRole(role: Role) = when (role) {
        Role.STUDENT -> roleStudent
        Role.TEACHER -> roleTeacher
        Role.ADMIN -> roleAdmin
    }

    fun translateStudents(count: Int) = if (count == 1) studentsPlural else studentsSingular
    fun translateExercises(count: Int) = if (count == 1) exerciseSingular else exercisePlural
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
    abstract val roleAdmin: String
    abstract val roleTeacher: String
    abstract val roleStudent: String
    abstract val accountData: String
    abstract val logOut: String


    // Sidenav
    abstract val newExercise: String
    abstract val newCourse: String
    abstract val allExercises: String
    abstract val exercises: String
    abstract val participants: String
    abstract val gradesLabel: String
    abstract val courseSettings: String
    abstract val linkAbout: String
    abstract val linkTOS: String


    // Courses page
    abstract val coursesTitle: String
    abstract val coursesTitleAdmin: String
    abstract val studentsSingular: String
    abstract val studentsPlural: String
    abstract val enrolledOnCourseAttrKey: String
    abstract val coursesSingular: String
    abstract val coursesPlural: String


    // Course exercises page
    abstract val completedBadgeLabel: String
    abstract val deadlineLabel: String

    // TODO: use in ezcoll
    abstract val completedLabel: String
    abstract val startedLabel: String
    abstract val ungradedLabel: String
    abstract val unstartedLabel: String


    // Course exercise page
    abstract val tabSubmit: String
    abstract val tabMySubmissions: String
    abstract val draftSaveFailedMsg: String
    abstract val exerciseClosedForSubmissions: String
    abstract val solutionEditorStatusDraft: String
    abstract val solutionEditorStatusSubmission: String
    abstract val submissionSingular: String
    abstract val submissionPlural: String


    abstract val tabExerciseLabel: String
    abstract val tabTestingLabel: String
    abstract val tabSubmissionsLabel: String
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
    abstract val teacherAssessmentLabel: String
    abstract val gradeLabel: String
    abstract val addAssessmentLink: String
    abstract val addAssessmentGradeLabel: String
    abstract val addAssessmentFeedbackLabel: String
    abstract val addAssessmentGradeValidErr: String
    abstract val addAssessmentButtonLabel: String
    abstract val assessmentAddedMsg: String
    abstract val submissionTimeLabel: String
    abstract val doSubmitAndCheck: String
    abstract val doSubmit: String
    abstract val submissionHeading: String
    abstract val latestSubmissionSuffix: String
    abstract val allSubmissionsLink: String
    abstract val loadingAllSubmissions: String
    abstract val oldSubmissionNote: String
    abstract val toLatestSubmissionLink: String
    abstract val aaTitle: String
    abstract val submitSuccessMsg: String

    abstract val autogradeException: String
    abstract val autogradeFailedMsg: String
    abstract val autogradeCreatedFiles: String
    abstract val autogradeStdIn: String
    abstract val autogradeStdOut: String
    abstract val autogradeNoChecksInTest: String


    // Exercise page
    abstract val testType: String

    // About page
    abstract val aboutS1: String
    abstract val aboutS2: String
    abstract val aboutS3: String
    abstract val aboutS4: String
    abstract val aboutS5: String
    abstract val aboutS6: String
    abstract val aboutSponsors: String
    abstract val statsAutograding: String
    abstract val statsSubmissions: String
    abstract val statsAccounts: String

    // Datetime
    abstract val today: String
    abstract val yesterday: String
    abstract val tomorrow: String
    abstract val monthList: List<String>
}

object EstStrings : TranslatableStrings() {
    override val otherLanguage = "In English"
    override val notFoundPageTitle = "Lehte ei leitud"
    override val notFoundPageMsg = "Siin pole midagi kasulikku näha. :("
    override val noPermissionForPageMsg = "Sul puudub õigus selle lehe vaatamiseks. :("
    override val noCourseAccessPageMsg = "Sul puudub ligipääs sellele kursusele. :("
    override val somethingWentWrong =
        "Midagi läks valesti... Proovi lehte uuendada ja kui viga kordub, siis võta ühendust administraatoriga."
    override val yes = "jah"
    override val no = "ei"
    override val myCourses = "Minu kursused"
    override val exerciseLibrary = "Ülesandekogu"
    override val gradedAutomatically = "Automaatselt hinnatud"
    override val gradedByTeacher = "Õpetaja poolt hinnatud"
    override val notGradedYet = "Hindamata"





    override val solutionCodeTabName = "lahendus"
    override val solutionEditorPlaceholder = "Kirjuta, kopeeri või lohista lahendus siia..."
    override val roleAdmin = "Admin"
    override val roleTeacher = "Õpetaja"
    override val roleStudent = "Õpilane"
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
    override val assessmentAddedMsg = "Hinnang lisatud"
    override val oldSubmissionNote = "See on vana esitus."
    override val toLatestSubmissionLink = "Vaata viimast esitust."
    override val loadingAllSubmissions = "Laen esitusi..."
    override val submissionHeading = "Esitus"
    override val latestSubmissionSuffix = "(viimane esitus)"
    override val allSubmissionsLink = "► Vaata kõiki esitusi"
    override val tabSubmit = "Esita"
    override val tabMySubmissions = "Minu esitused"
    override val draftSaveFailedMsg = "Mustandi salvestamine ebaõnnestus"
    override val exerciseClosedForSubmissions = "See ülesanne on suletud ja ei luba enam uusi esitusi"
    override val solutionEditorStatusDraft = "Esitamata mustand"
    override val solutionEditorStatusSubmission = "Viimane esitus"
    override val submissionSingular = "esitus"
    override val submissionPlural = "esitust"
    override val doSubmitAndCheck = "Esita ja kontrolli"
    override val doSubmit = "Esita"
    override val softDeadlineLabel = "Tähtaeg"
    override val hardDeadlineLabel = "Sulgemise aeg"
    override val graderTypeLabel = "Hindamine"
    override val thresholdLabel = "Lävend"
    override val studentVisibleLabel = "Õpilastele nähtav"
    override val studentVisibleFromTimeLabel = "Muutub nähtavaks"
    override val assStudentVisibleLabel = "Hinnangud õpilastele nähtavad"
    override val lastModifiedLabel = "Viimati muudetud"
    override val submissionTimeLabel = "Esitamise aeg"

    override val studentsSingular = "õpilast"
    override val studentsPlural = "õpilane"
    override val enrolledOnCourseAttrKey = "Kursusel on"

    override val coursesTitleAdmin = "Kõik kursused"
    override val accountData = "Konto seaded"
    override val logOut = "Logi välja"
    override val newExercise = "Uus ülesanne"
    override val newCourse = "Uus kursus"
    override val allExercises = "Kõik ülesanded"
    override val exercises = "Ülesanded"
    override val participants = "Osalejad"
    override val gradesLabel = "Hinded"
    override val courseSettings = "Kursuse sätted"
    override val linkAbout = "Lahendusest"
    override val linkTOS = "Kasutustingimused"
    override val coursesTitle = "Minu kursused"
    override val deadlineLabel = "Tähtaeg"

    override val completedLabel = "Lõpetanud"
    override val startedLabel = "Nässu läinud"
    override val ungradedLabel = "Hindamata"
    override val unstartedLabel = "Esitamata"
    override val tabExerciseLabel = "Ülesanne"
    override val tabTestingLabel = "Katsetamine"
    override val tabSubmissionsLabel = "Esitused"
    override val autoAssessmentLabel = "Automaatsed testid"
    override val teacherAssessmentLabel = "Õpetaja kommentaar"
    override val gradeLabel = "Punktid"
    override val doAutoAssess = "Kontrolli"
    override val autoAssessing = "Kontrollin..."
    override val tryAgain = "Proovi uuesti"
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
    override val autogradeException = "Programmi käivitamisel tekkis viga:"
    override val autogradeFailedMsg = """

           ¯\_(ツ)_/¯
           
Automaatne testimine ebaõnnestus.
Kedagi on probleemist ilmselt juba teavitatud, 
ole hea ja proovi hiljem uuesti.
        """
    override val autogradeCreatedFiles = "Enne programmi käivitamist lõin failid:"
    override val autogradeStdIn = "Andsin programmile sisendid:"
    override val autogradeStdOut = "Programmi täielik väljund oli:"
    override val autogradeNoChecksInTest = "Siin pole midagi kontrollida. See vist tähendab, et kõik on OK?"

    override val exerciseSingular = "ülesanne"
    override val exercisePlural = "ülesannet"
    override val permissionP = "Läbikäija"
    override val permissionPR = "Vaataja"
    override val permissionPRA = "Lisaja"
    override val permissionPRAW = "Muutja"
    override val permissionPRAWM = "Moderaator"
    override val testType = "Testi tüüp"

    override val coursesSingular = "kursus"
    override val coursesPlural = "kursust"
    override val completedBadgeLabel = "Tehtud!"

    override val aboutS1="Lahenduse keskkonda haldab ja arendab"
    override val aboutS2="Tartu Ülikooli arvutiteaduse instituut"
    override val aboutS3="Lahendus põhineb vabavaralisel rakendusel"
    override val aboutS4=", mida arendatakse samuti arvutiteaduse instituudis"
    override val aboutS5="Kui sul on Lahenduse kasutamise või arenduse kohta küsimusi, või kui leidsid kuskilt vea, siis tule räägi sellest"
    override val aboutS6="Lahenduse Discordi serveris"
    override val aboutSponsors="Lahenduse ja easy arendust ning ülesannete loomist on toetanud"
    override val statsAutograding="Hetkel kontrollitavaid lahendusi"
    override val statsSubmissions="Esitusi kokku"
    override val statsAccounts="Kasutajaid kokku"

    override val today = "täna"
    override val yesterday = "eile"
    override val tomorrow = "homme"
    override val monthList = listOf(
        "jaanuar", "veebruar", "märts", "aprill", "mai", "juuni",
        "juuli", "august", "september", "oktoober", "november", "detsember"
    )
}