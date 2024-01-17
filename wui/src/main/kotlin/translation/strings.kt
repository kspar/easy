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
    abstract val added: String
    abstract val goToIt: String
    abstract val cancel: String
    abstract val solutionCodeTabName: String
    abstract val solutionEditorPlaceholder: String
    abstract val exerciseSingular: String
    abstract val exercisePlural: String
    abstract val doEditTitle: String
    abstract val doMove: String
    abstract val moveUp: String
    abstract val moveDown: String
    abstract val moving: String
    abstract val doRemove: String
    abstract val removing: String
    abstract val removed: String
    abstract val doDelete: String
    abstract val deleting: String
    abstract val deleted: String
    abstract val doRestore: String
    abstract val doEdit: String
    abstract val doDuplicate: String
    abstract val doExpand: String
    abstract val doAutoAssess: String
    abstract val autoAssessing: String
    abstract val tryAgain: String
    abstract val accountGroup: String
    abstract val doDownload: String
    abstract val downloading: String
    abstract val feedback: String
    abstract val type: String
    abstract val value: String
    abstract val loading: String

    abstract val ezcollEmpty: String
    abstract val ezcollNoMatchingItems: String
    abstract val ezcollApply: String
    abstract val ezcollDoFilter: String
    abstract val ezcollDoSort: String
    abstract val ezcollRemoveFilters: String
    abstract val ezcollShown: String
    abstract val ezcollSelected: String

    abstract val unorderedListExpand: String

    abstract val logIn: String
    abstract val authFailed: String
    abstract val authRefreshFailed: String
    abstract val serverErrorMsg: String

    fun translateBoolean(bool: Boolean) = if (bool) yes else no
    fun translateRole(role: Role) = when (role) {
        Role.STUDENT -> roleStudent
        Role.TEACHER -> roleTeacher
        Role.ADMIN -> roleAdmin
    }

    fun translateStudents(count: Int) = if (count == 1) studentsSingular else studentsPlural
    fun translateExercises(count: Int) = if (count == 1) exerciseSingular else exercisePlural
    fun translatePermission(permission: DirAccess) =
        when (permission) {
            DirAccess.P -> permissionP
            DirAccess.PR -> permissionPR
            DirAccess.PRA -> permissionPRA
            DirAccess.PRAW -> permissionPRAW
            DirAccess.PRAWM -> permissionPRAWM
        }

    abstract fun translateServerError(httpStatus: Short, code: String?, msg: String): String

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
    abstract val newCourse: String
    abstract val allExercises: String
    abstract val exercises: String
    abstract val openInLib: String
    abstract val similarityAnalysis: String
    abstract val exerciseSettings: String
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
    abstract val hidden: String
    abstract val doHide: String
    abstract val doReveal: String
    abstract val revealed: String
    abstract val doRemoveFromCourse: String
    abstract val submissionsWillBeDeleted: String
    abstract val removeExercise: String
    abstract val removeExercisesPlural: String
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
    abstract val studentVisibleFromTimeLabel: String
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
    abstract val allStudents: String
    abstract val total: String
    abstract val feedbackEmailNote: String
    abstract val noVisibleExerciseError: String
    abstract val autogradeException: String
    abstract val autogradeFailedMsg: String
    abstract val autogradeCreatedFiles: String
    abstract val autogradeStdIn: String
    abstract val autogradeStdOut: String
    abstract val autogradeNoChecksInTest: String


    // Exercise library page
    abstract val newExercise: String
    abstract val newDirectory: String
    abstract val dirSettings: String
    abstract val deleteDir: String
    abstract val cannotDeleteNonemptyDir: String
    abstract val deleteExercise: String
    abstract val cannotDeleteExerciseUsedOnCourse: String
    abstract val share: String
    abstract val permissionsChanged: String
    abstract val shared: String
    abstract val private: String
    abstract val directoryName: String
    abstract val exerciseTitle: String
    abstract val addToThisCourse: String
    abstract val shareUserFieldHelp: String
    abstract val allUsers: String
    abstract val inheritedFrom: String
    abstract val thisDirectorySuffix: String
    abstract val removeAccess: String
    abstract val msgExerciseCreated: String
    abstract val libUsedOnCourses1: String
    abstract val libUsedOnCourses2: String
    abstract val addToCourse: String
    abstract val itemSingular: String
    abstract val itemPlural: String
    abstract val grading: String
    abstract val gradingAuto: String
    abstract val gradingTeacher: String
    abstract val sortByName: String
    abstract val sortByModified: String
    abstract val sortByPopularity: String


    // Exercise page
    abstract val tabExercise: String
    abstract val tabAutoassess: String
    abstract val tabTesting: String
    abstract val addToCourseModalTitle: String
    abstract val addToCourseModalText1: String
    abstract val addToCourseModalText2: String
    abstract val exerciseAlreadyOnCourse: String
    abstract val autoAssessTypeImgRec: String
    abstract val usedOnCourses: String
    abstract val hiddenCourses: String
    abstract val modifiedAt: String
    abstract val exerciseCreatedAtPhrase: String
    abstract val exerciseSaved: String
    abstract val noAccessToLibExerciseMsg: String
    abstract val updatedInEditMsg: String
    abstract val mergeConflictMsg: String
    abstract val exerciseUnsavedChangesMsg: String
    abstract val lastTestingAttempt: String
    abstract val testingEditedWarnMsg: String
    abstract val autoassessType: String
    abstract val allowedExecTime: String
    abstract val allowedExecTimeField: String
    abstract val secAbbrev: String
    abstract val allowedExecMem: String
    abstract val allowedExecMemField: String


    // TSL UI
    abstract val tslPlaceholderTest: Pair<String, String>
    abstract val tslProgExecTest: Pair<String, String>
    abstract val tslFuncCallTest: Pair<String, String>
    abstract val tslNotImplTest: Pair<String, String>

    abstract val tslAddTest: String
    abstract val tslTestsTab: String
    abstract val tslSpecTab: String
    abstract val tslGeneratedTab: String
    abstract val tslTestTitle: String
    abstract val tslTestType: String
    abstract val tslCopySuffix: String
    abstract val tslInputs: String
    abstract val tslChecks: String

    abstract val tslStdin: String
    abstract val tslStdinFieldHelp: String
    abstract val tslStdins: String

    abstract val tslInputFile: String
    abstract val tslInputFileName: String
    abstract val tslInputFileContent: String
    abstract val tslInputFileSent1: String

    abstract val tslFuncArgsFieldHelp: String
    abstract val tslFuncName: String
    abstract val tslFuncArgs: String

    abstract val tslStdoutCheck: String
    abstract val tslStdoutOutputs: String
    abstract val tslStdoutCheckContainsAllPass: String
    abstract val tslStdoutCheckContainsAllFail: String
    abstract val tslStdoutContainsAll: String
    abstract val tslStdoutContainsOne: String
    abstract val tslStdoutNotContainsOne: String
    abstract val tslStdoutNotContainsAll: String
    abstract val tslStdoutDataString: String
    abstract val tslStdoutDataNumber: String
    abstract val tslStdoutDataLine: String
    abstract val tslStdoutOrdered: String
    abstract val tslStdoutCheckSent1: String
    abstract val tslStdoutCheckSent2: String

    abstract val tslReturnCheck: String
    abstract val tslReturnCheckPrefixMsg: String
    abstract val tslReturnCheckValueHelp: String
    abstract val tslReturnCheckPass: String
    abstract val tslReturnCheckFail: String


    // Similarity page
    abstract val exercise: String
    abstract val findSimilarities: String
    abstract val searching: String
    abstract val topSimilarPairs: String
    abstract val diceSimilarity: String
    abstract val levenshteinSimilarity: String


    // Exercise embed page
    abstract val noAutogradeWarning: String


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
    override val notFoundPageMsg = "Siin pole midagi kasulikku näha :("
    override val noPermissionForPageMsg = "Sul puudub õigus selle lehe vaatamiseks :("
    override val noCourseAccessPageMsg = "Sul puudub ligipääs sellele kursusele :("
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
    override val added = "Lisatud"
    override val goToIt = "Vaata"
    override val cancel = "Tühista"
    override val doEditTitle = "Muuda pealkirja"
    override val doMove = "Liiguta"
    override val moveUp = "Liiguta üles"
    override val moveDown = "Liiguta alla"
    override val moving = "Liigutan..."
    override val doRemove = "Eemalda"
    override val removing = "Eemaldan..."
    override val removed = "Eemaldatud"
    override val doDelete = "Kustuta"
    override val deleting = "Kustutan..."
    override val deleted = "Kustutatud"
    override val doRestore = "Taasta"
    override val doEdit = "Muuda"
    override val doDuplicate = "Loo koopia"
    override val doExpand = "Laienda"
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
    override val studentVisibleFromTimeLabel = "Muutub nähtavaks"
    override val submissionTimeLabel = "Esitamise aeg"

    override val studentsSingular = "õpilane"
    override val studentsPlural = "õpilast"
    override val enrolledOnCourseAttrKey = "Kursusel on"

    override val coursesTitleAdmin = "Kõik kursused"
    override val accountData = "Konto seaded"
    override val logOut = "Logi välja"
    override val newExercise = "Uus ülesanne"
    override val newDirectory = "Uus kaust"
    override val dirSettings = "Muuda nime"
    override val deleteDir = "Kustuta kaust"
    override val cannotDeleteNonemptyDir = "Kausta ei saa kustutada, sest see pole tühi"
    override val deleteExercise = "Kustuta ülesanne"
    override val cannotDeleteExerciseUsedOnCourse =
        "Ülesannet ei saa kustutada, sest see on vähemalt ühel kursusel kasutusel"
    override val share = "Jagamine"
    override val permissionsChanged = "Õigused muudetud"
    override val shared = "Jagatud"
    override val private = "Privaatsed"
    override val directoryName = "Kausta nimi"
    override val exerciseTitle = "Ülesande pealkiri"
    override val addToThisCourse = "Lisa sellele kursusele"
    override val shareUserFieldHelp = "Jagamiseks sisesta kasutaja email"
    override val allUsers = "Kõik kasutajad"
    override val inheritedFrom = "Päritud kaustalt"
    override val thisDirectorySuffix = "(see kaust)"
    override val removeAccess = "Eemalda juurdepääs"
    override val newCourse = "Uus kursus"
    override val allExercises = "Kõik ülesanded"
    override val exercises = "Ülesanded"
    override val openInLib = "Ava ülesandekogus"
    override val similarityAnalysis = "Lahenduste võrdlus"
    override val exerciseSettings = "Ülesande sätted"
    override val participants = "Osalejad"
    override val gradesLabel = "Hinded"
    override val courseSettings = "Kursuse sätted"
    override val linkAbout = "Lahendusest"
    override val linkTOS = "Kasutustingimused"
    override val coursesTitle = "Minu kursused"
    override val deadlineLabel = "Tähtaeg"
    override val hidden = "Peidetud"
    override val doHide = "Peida"
    override val doReveal = "Avalikusta"
    override val revealed = "Avalikustatud"
    override val doRemoveFromCourse = "Eemalda kursuselt"
    override val submissionsWillBeDeleted = "Õpilaste esitused kustutatakse."
    override val removeExercise = "Eemalda ülesanne"
    override val removeExercisesPlural = "ülesannet"

    override val completedLabel = "lahendatud"
    override val startedLabel = "nässu läinud"
    override val ungradedLabel = "hindamata"
    override val unstartedLabel = "esitamata"
    override val tabExerciseLabel = "Ülesanne"
    override val tabTestingLabel = "Katsetamine"
    override val tabSubmissionsLabel = "Esitused"
    override val autoAssessmentLabel = "Automaatsed testid"
    override val teacherAssessmentLabel = "Õpetaja kommentaar"
    override val gradeLabel = "Punktid"
    override val doAutoAssess = "Kontrolli"
    override val autoAssessing = "Kontrollin..."
    override val tryAgain = "Proovi uuesti"
    override val accountGroup = "Rühm"
    override val doDownload = "Lae alla"
    override val downloading = "Laen..."
    override val feedback = "Tagasiside"
    override val type = "Tüüp"
    override val value = "Väärtus"
    override val loading = "Laen..."
    override val ezcollEmpty = "Siin pole veel midagi näidata"
    override val ezcollNoMatchingItems = "Valitud filtritele ei vasta ükski rida"
    override val ezcollApply = "Rakenda..."
    override val ezcollDoFilter = "Filtreeri..."
    override val ezcollDoSort = "Järjesta..."
    override val ezcollRemoveFilters = "Eemalda filtrid"
    override val ezcollShown = "kuvatud"
    override val ezcollSelected = "valitud"
    override val unorderedListExpand = "Näita kõiki..."
    override val logIn = "Logi sisse"
    override val authFailed = "Autentimine ebaõnnestus"
    override val authRefreshFailed = "Sessiooni uuendamine ebaõnnestus. Jätkamiseks tuleb uuesti sisse logida."
    override val serverErrorMsg =
        "Midagi läks valesti, palun proovi hiljem uuesti. Server tagastas ootamatu vastuse HTTP staatusega"

    override fun translateServerError(httpStatus: Short, code: String?, msg: String) =
        if (code != null)
            """
                Midagi läks valesti, palun proovi hiljem uuesti. 
                Server tagastas vea:
                HTTP staatus: $httpStatus
                Kood: $code
                Sõnum: $msg
            """.trimIndent()
        else
            """
                Midagi läks valesti, palun proovi hiljem uuesti. 
                Server tagastas ootamatu vastuse:
                HTTP staatus: $httpStatus
                Vastus: $msg
            """.trimIndent()

    override val addAssessmentLink = "► Lisa hinnang"
    override val closeToggleLink = "▼ Sulge"
    override val graderTypeAuto = "automaatne"
    override val graderTypeTeacher = "manuaalne"
    override val addAssessmentGradeLabel = "Hinne (0-100)"
    override val addAssessmentFeedbackLabel = "Kommentaar"
    override val addAssessmentGradeValidErr = "Hinne peab olema arv 0 ja 100 vahel"
    override val addAssessmentButtonLabel = "Lisa hinnang"
    override val aaTitle = "Automaatkontroll"
    override val submitSuccessMsg = "Lahendus esitatud"
    override val allStudents = "Kõik õpilased"
    override val total = "kokku"
    override val feedbackEmailNote = "Kui lisasid kommentaari, siis saadetakse see õpilasele emailiga."
    override val noVisibleExerciseError = "Seda ülesannet ei eksisteeri või see on peidetud"
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
    override val msgExerciseCreated = "Ülesanne loodud"
    override val modifiedAt = "Viimane muudatus"
    override val exerciseCreatedAtPhrase = "loodud"
    override val noAccessToLibExerciseMsg = "Sul pole ülesandekogus sellele ülesandele ligipääsu"
    override val updatedInEditMsg = "Seda ülesannet on vahepeal muudetud, näitan uut versiooni"
    override val mergeConflictMsg =
        "Seda ülesannet on vahepeal muudetud ja lokaalsed muudatused lähevad varem tehtud muudatustega konflikti. Kas soovid vahepeal tehtud muudatused oma lokaalsete muudatustega üle kirjutada?"
    override val exerciseUnsavedChangesMsg =
        "Siin lehel on salvestamata muudatusi. Kas oled kindel, et soovid muutmise lõpetada ilma salvestamata?"
    override val lastTestingAttempt = "Viimane katsetus"
    override val testingEditedWarnMsg =
        "Kui oled automaatkontrollis muudatusi teinud, siis pead enne nende jõustumist ülesande salvestama."

    override val autoassessType = "Automaatkontrolli tüüp"
    override val allowedExecTime = "Lubatud käivitusaeg"
    override val allowedExecTimeField = "Käivitusaeg (s)"
    override val secAbbrev = "s"
    override val allowedExecMem = "Lubatud mälukasutus"
    override val allowedExecMemField = "Mälukasutus (MB)"
    override val tslPlaceholderTest = "-" to "Uus test"
    override val tslProgExecTest = "Programmi käivitus" to "Programmi käivituse test"
    override val tslFuncCallTest = "Funktsiooni väljakutse" to "Funktsiooni väljakutse test"
    override val tslNotImplTest = "Ära puutu!" to "Kasutajaliideses implementeerimata test"
    override val tslAddTest = "Lisa test"
    override val tslTestsTab = "Testid"
    override val tslSpecTab = "TSL"
    override val tslGeneratedTab = "Genereeritud skriptid"
    override val tslTestTitle = "Testi pealkiri"
    override val exerciseSaved = "Ülesanne salvestatud"
    override val libUsedOnCourses1 = "Kasutusel"
    override val libUsedOnCourses2 = "kursusel"
    override val addToCourse = "Lisa kursusele"
    override val itemSingular = "asi"
    override val itemPlural = "asja"
    override val grading = "Hindamine"
    override val gradingAuto = "Automaatkontrolliga"
    override val gradingTeacher = "Ilma automaatkontrollita"
    override val sortByName = "Nime järgi"
    override val sortByModified = "Muutmisaja järgi"
    override val sortByPopularity = "Populaarsuse järgi"
    override val tabExercise = "Ülesanne"
    override val tabAutoassess = "Automaatkontroll"
    override val tabTesting = "Katsetamine"
    override val addToCourseModalTitle = "Lisa ülesanne kursusele"
    override val addToCourseModalText1 = "Lisa "
    override val addToCourseModalText2 = " kursusele:"
    override val exerciseAlreadyOnCourse = "See ülesanne on kursusel juba olemas"
    override val autoAssessTypeImgRec = "tkinter pildituvastus"
    override val usedOnCourses = "Kasutusel kursustel"
    override val hiddenCourses = "peidetud kursust"
    override val exerciseSingular = "ülesanne"
    override val exercisePlural = "ülesannet"
    override val permissionP = "Läbikäija"
    override val permissionPR = "Vaataja"
    override val permissionPRA = "Lisaja"
    override val permissionPRAW = "Muutja"
    override val permissionPRAWM = "Moderaator"
    override val tslTestType = "Testi tüüp"
    override val tslCopySuffix = "(koopia)"
    override val tslInputs = "Sisendandmed"
    override val tslChecks = "Kontrollid"
    override val tslStdin = "Kasutaja sisend"
    override val tslStdinFieldHelp = "Õpilase programmile antavad kasutaja sisendid, iga sisend eraldi real"
    override val tslStdins = "Kasutaja sisendid"
    override val tslInputFile = "Sisendfail"
    override val tslInputFileName = "Faili nimi"
    override val tslInputFileContent = "Faili sisu"
    override val tslInputFileSent1 = "Tekstifail"
    override val tslFuncArgsFieldHelp = "Argumendid eraldi ridadel ja Pythoni süntaksis, nt sõne \"abc\" või arv 42"
    override val tslFuncName = "Funktsiooni nimi"
    override val tslFuncArgs = "Funktsiooni argumendid"
    override val tslStdoutCheck = "Väljundi kontroll"
    override val tslStdoutOutputs = "Oodatavad õpilase programmi väljundid, iga väärtus eraldi real"
    override val tslStdoutCheckContainsAllPass = "Leidsin programmi väljundist õige vastuse {expected}"
    override val tslStdoutCheckContainsAllFail = "Ei leidnud programmi väljundist oodatud vastust {expected}"
    override val tslStdoutContainsAll = "leiduvad kõik"
    override val tslStdoutContainsOne = "leidub vähemalt üks"
    override val tslStdoutNotContainsOne = "ei leidu vähemalt ühte"
    override val tslStdoutNotContainsAll = "ei leidu mitte ühtegi"
    override val tslStdoutDataString = "sõnedest"
    override val tslStdoutDataNumber = "arvudest"
    override val tslStdoutDataLine = "ridadest"
    override val tslStdoutOrdered = "Väärtuste järjekord peab oleme sama"
    override val tslStdoutCheckSent1 = "Väljundis"
    override val tslStdoutCheckSent2 = "järgmistest"
    override val tslReturnCheck = "Tagastusväärtuse kontroll"
    override val tslReturnCheckPrefixMsg = "Tagastusväärtus peab olema:"
    override val tslReturnCheckValueHelp = "Oodatav funktsiooni tagastusväärtus Pythoni süntaksis"
    override val tslReturnCheckPass = "Funktsioon tagastas õige väärtuse {expected}"
    override val tslReturnCheckFail = "Ootasin, et funktsioon tagastaks {expected}, aga tagastas {actual}"
    override val exercise = "Ülesanne"
    override val findSimilarities = "Leia sarnasused"
    override val searching = "Otsin..."
    override val topSimilarPairs = "Kõige sarnasemad paarid"
    override val diceSimilarity = "Sørensen–Dice'i sarnasus"
    override val levenshteinSimilarity = "Levenshteini sarnasus"
    override val noAutogradeWarning =
        "Sellel ülesandel pole automaatseid teste. Lisa testid või eemalda `submit` query parameeter."

    override val coursesSingular = "kursus"
    override val coursesPlural = "kursust"
    override val completedBadgeLabel = "Tehtud!"

    override val aboutS1 = "Lahenduse keskkonda haldab ja arendab"
    override val aboutS2 = "Tartu Ülikooli arvutiteaduse instituut"
    override val aboutS3 = "Lahendus põhineb vabavaralisel rakendusel"
    override val aboutS4 = ", mida arendatakse samuti arvutiteaduse instituudis"
    override val aboutS5 =
        "Kui sul on Lahenduse kasutamise või arenduse kohta küsimusi, või kui leidsid kuskilt vea, siis tule räägi sellest"
    override val aboutS6 = "Lahenduse Discordi serveris"
    override val aboutSponsors = "Lahenduse ja easy arendust ning ülesannete loomist on toetanud"
    override val statsAutograding = "Hetkel kontrollitavaid lahendusi"
    override val statsSubmissions = "Esitusi kokku"
    override val statsAccounts = "Kasutajaid kokku"

    override val today = "täna"
    override val yesterday = "eile"
    override val tomorrow = "homme"
    override val monthList = listOf(
        "jaanuar", "veebruar", "märts", "aprill", "mai", "juuni",
        "juuli", "august", "september", "oktoober", "november", "detsember"
    )
}


object EngStrings : TranslatableStrings() {
    override val otherLanguage = "Eesti keeles"
    override val notFoundPageTitle = "Page not found"
    override val notFoundPageMsg = "Nothing to see here :("
    override val noPermissionForPageMsg = "It seems like you have no permission to look here :("
    override val noCourseAccessPageMsg = "It seems like you have no access to this course :("
    override val somethingWentWrong =
        "Something went wrong... Try to refresh the page and if it doesn't get any better, please contact an administrator."
    override val yes = "Yes"
    override val no = "No"
    override val myCourses = "My courses"
    override val exerciseLibrary = "Exercise library"
    override val gradedAutomatically = "Graded automatically"
    override val gradedByTeacher = "Graded by teacher"
    override val notGradedYet = "Not graded"
    override val closeToggleLink = "▼ Close"
    override val doSave = "Save"
    override val saving = "Saving..."
    override val doAdd = "Add"
    override val adding = "Adding..."
    override val added = "Added"
    override val goToIt = "Go"
    override val cancel = "Cancel"
    override val solutionCodeTabName = "lahendus"
    override val solutionEditorPlaceholder = "Write, paste or drag your solution here..."
    override val exerciseSingular = "exercise"
    override val exercisePlural = "exercises"
    override val doEditTitle = "Edit title"
    override val doMove = "Move"
    override val moveUp = "Move up"
    override val moveDown = "Move down"
    override val moving = "Moving..."
    override val doRemove = "Remove"
    override val removing = "Removing..."
    override val removed = "Removed"
    override val doDelete = "Delete"
    override val deleting = "Deleting..."
    override val deleted = "Deleted"
    override val doRestore = "Restore"
    override val doEdit = "Edit"
    override val doDuplicate = "Copy"
    override val doExpand = "Expand"

    override val permissionP = "Passthrough"
    override val permissionPR = "Viewer"
    override val permissionPRA = "Adder"
    override val permissionPRAW = "Editor"
    override val permissionPRAWM = "Moderator"

    override val roleAdmin = "Admin"
    override val roleTeacher = "Teacher"
    override val roleStudent = "Student"
    override val accountData = "Account settings"
    override val logOut = "Log out"

    override val newExercise = "New exercise"
    override val newDirectory = "New directory"
    override val dirSettings = "Change name"
    override val deleteDir = "Delete directory"
    override val cannotDeleteNonemptyDir = "The directory is not empty and cannot be deleted"
    override val deleteExercise = "Delete exercise"
    override val cannotDeleteExerciseUsedOnCourse =
        "This exercise is still used on at least one course and cannot be deleted"
    override val share = "Share"
    override val permissionsChanged = "Permissions changed"
    override val shared = "Shared"
    override val private = "Private"
    override val directoryName = "Directory name"
    override val exerciseTitle = "Exercise title"
    override val addToThisCourse = "Add to this course"
    override val shareUserFieldHelp = "Enter user's email to share"
    override val allUsers = "All users"
    override val inheritedFrom = "Inherited from"
    override val thisDirectorySuffix = "(this directory)"
    override val removeAccess = "Remove access"
    override val newCourse = "New course"
    override val allExercises = "All exercises"
    override val exercises = "Exercises"
    override val openInLib = "Open in exercise library"
    override val similarityAnalysis = "Similarity analysis"
    override val exerciseSettings = "Exercise settings"
    override val participants = "Participants"
    override val gradesLabel = "Grades"
    override val courseSettings = "Course settings"
    override val linkAbout = "About Lahendus"
    override val linkTOS = "Terms"

    override val coursesTitle = "My courses"
    override val coursesTitleAdmin = "All courses"
    override val studentsSingular = "student"
    override val studentsPlural = "students"
    override val enrolledOnCourseAttrKey = "Enrolled"
    override val coursesSingular = "course"
    override val coursesPlural = "courses"
    override val completedBadgeLabel = "Completed!"

    override val deadlineLabel = "Deadline"
    override val hidden = "Hidden"
    override val doHide = "Hide"
    override val doReveal = "Reveal"
    override val revealed = "Revealed"
    override val doRemoveFromCourse = "Remove from course"
    override val submissionsWillBeDeleted = "Students' submissions will be deleted."
    override val removeExercise = "Remove exercise"
    override val removeExercisesPlural = "exercises"
    override val completedLabel = "completed"
    override val startedLabel = "unsuccessful"
    override val ungradedLabel = "ungraded"
    override val unstartedLabel = "not submitted"

    override val tabExerciseLabel = "Exercise"
    override val tabTestingLabel = "Testing"
    override val tabSubmissionsLabel = "Submissions"
    override val tabSubmit = "Submit"
    override val tabMySubmissions = "My submissions"
    override val draftSaveFailedMsg = "Saving the draft failed"
    override val exerciseClosedForSubmissions = "This exercise is closed and does not allow any new submissions"
    override val solutionEditorStatusDraft = "Unsubmitted draft"
    override val solutionEditorStatusSubmission = "Latest submission"
    override val submissionSingular = "submission"
    override val submissionPlural = "submissions"
    override val softDeadlineLabel = "Deadline"
    override val hardDeadlineLabel = "Submissions allowed until"
    override val studentVisibleFromTimeLabel = "Visible from"
    override val graderTypeAuto = "automatic"
    override val graderTypeTeacher = "manual"
    override val autoAssessmentLabel = "Automated tests"
    override val teacherAssessmentLabel = "Teacher feedback"
    override val gradeLabel = "Points"
    override val doAutoAssess = "Check"
    override val autoAssessing = "Checking..."
    override val tryAgain = "Try again"
    override val accountGroup = "Group"
    override val doDownload = "Download"
    override val downloading = "Downloading..."
    override val feedback = "Feedback"
    override val type = "Type"
    override val value = "Value"
    override val loading = "Loading..."
    override val ezcollEmpty = "Nothing to see here yet"
    override val ezcollNoMatchingItems = "No items match the selected filters"
    override val ezcollApply = "Apply..."
    override val ezcollDoFilter = "Filter..."
    override val ezcollDoSort = "Sort..."
    override val ezcollRemoveFilters = "Remove filters"
    override val ezcollShown = "shown"
    override val ezcollSelected = "selected"
    override val unorderedListExpand = "Show all..."
    override val logIn = "Log in"
    override val authFailed = "Authentication failed"
    override val authRefreshFailed = "Refreshing your session failed, log in to continue"
    override val serverErrorMsg = "Something went wrong, please try again later. The server responded with HTTP status"

    override fun translateServerError(httpStatus: Short, code: String?, msg: String) =
        if (code != null)
            """
                Something went wrong, please try again later.
                The server returned an error:
                HTTP status: $httpStatus
                Code: $code
                Message: $msg
            """.trimIndent()
        else
            """
                Something went wrong, please try again later.
                The server returned an expected response:
                HTTP status: $httpStatus
                Response: $msg
            """.trimIndent()

    override val addAssessmentLink = "► Add assessment"
    override val addAssessmentGradeLabel = "Grade (0-100)"
    override val addAssessmentFeedbackLabel = "Comment"
    override val addAssessmentGradeValidErr = "The grade has to be an integer between 0 and 100"
    override val addAssessmentButtonLabel = "Add assessment"
    override val assessmentAddedMsg = "Assessment added"
    override val submissionTimeLabel = "Submission time"
    override val doSubmitAndCheck = "Submit and check"
    override val doSubmit = "Submit"
    override val submissionHeading = "Submission"
    override val latestSubmissionSuffix = "(latest submission)"
    override val allSubmissionsLink = "► View all submissions"
    override val loadingAllSubmissions = "Loading submissions..."
    override val oldSubmissionNote = "This is an old submission."
    override val toLatestSubmissionLink = "View the latest submission."
    override val aaTitle = "Automated tests"
    override val submitSuccessMsg = "Solution submitted"
    override val allStudents = "All students"
    override val total = "total"
    override val feedbackEmailNote = "If you added a comment then the student will be notified by email."
    override val noVisibleExerciseError = "This exercise does not exist or is hidden"
    override val autogradeException = "There was an exception during the program's execution:"
    override val autogradeFailedMsg = """

         ¯\_(ツ)_/¯
           
Automatic testing failed.
Someone has probably already been notified 
of the issue, please try again later.
        """
    override val autogradeCreatedFiles = "Before running the program, the following files were created:"
    override val autogradeStdIn = "Inputs provided to the program:"
    override val autogradeStdOut = "The program's full output:"
    override val autogradeNoChecksInTest = "There weren't any checks to run. I guess that means we're fine?"
    override val msgExerciseCreated = "Exercise created"
    override val modifiedAt = "Last modified"
    override val exerciseCreatedAtPhrase = "created"
    override val exerciseSaved = "Exercise saved"
    override val noAccessToLibExerciseMsg = "You do not have access to this exercise in the exercise library"
    override val updatedInEditMsg = "This exercise has been modified, showing new version"
    override val mergeConflictMsg =
        "This exercise has been modified and the changes conflict with your local changes. Would you like to overwrite the modified exercise with your local changes?"
    override val exerciseUnsavedChangesMsg =
        "You have unsaved changes. Are you sure you wish to stop editing without saving?"
    override val lastTestingAttempt = "Last attempt"
    override val testingEditedWarnMsg =
        "If you've made changes in the automated tests, then you have to save the exercise for the changes to take effect."
    override val autoassessType = "Autoassessment type"
    override val allowedExecTime = "Allowed execution time"
    override val allowedExecTimeField = "Allowed time (sec)"
    override val secAbbrev = "sec"
    override val allowedExecMem = "Allowed memory usage"
    override val allowedExecMemField = "Allowed memory (MB)"
    override val tslPlaceholderTest = "-" to "New test"
    override val tslProgExecTest = "Program execution" to "Program execution test"
    override val tslFuncCallTest = "Function call" to "Function call test"
    override val tslNotImplTest = "Don't touch!" to "Test not implemented in the UI"
    override val tslAddTest = "Add test"
    override val tslTestsTab = "Tests"
    override val tslSpecTab = "TSL"
    override val tslGeneratedTab = "Generated scripts"
    override val tslTestTitle = "Test title"
    override val libUsedOnCourses1 = "Used on"
    override val libUsedOnCourses2 = "courses"
    override val addToCourse = "Add to course"
    override val itemSingular = "item"
    override val itemPlural = "items"
    override val grading = "Grading"
    override val gradingAuto = "With automatic tests"
    override val gradingTeacher = "Without automatic tests"
    override val sortByName = "By name"
    override val sortByModified = "By last modification"
    override val sortByPopularity = "By popularity"
    override val tabExercise = "Exercise"
    override val tabAutoassess = "Automated tests"
    override val tabTesting = "Testing"
    override val addToCourseModalTitle = "Add exercise to course"
    override val addToCourseModalText1 = "Add "
    override val addToCourseModalText2 = " to course:"
    override val exerciseAlreadyOnCourse = "This exercise already exists on this course"
    override val autoAssessTypeImgRec = "tkinter image recognition"
    override val usedOnCourses = "Used on courses"
    override val hiddenCourses = "hidden courses"

    override val tslTestType = "Test type"
    override val tslCopySuffix = "(copy)"
    override val tslInputs = "Inputs"
    override val tslChecks = "Checks"
    override val tslStdin = "User input"
    override val tslStdinFieldHelp = "Standard inputs provided to the program, each input on a separate line"
    override val tslStdins = "User inputs"
    override val tslInputFile = "Input file"
    override val tslInputFileName = "File name"
    override val tslInputFileContent = "File content"
    override val tslInputFileSent1 = "Text file"
    override val tslFuncArgsFieldHelp =
        "Arguments on separate lines and in Python syntax, e.g. string \"abc\" or integer 42"
    override val tslFuncName = "Function name"
    override val tslFuncArgs = "Function arguments"
    override val tslStdoutCheck = "Standard output check"
    override val tslStdoutOutputs = "Expected program outputs, each value on a separate line"
    override val tslStdoutCheckContainsAllPass = "Found the expected value in the program's output: {expected}"
    override val tslStdoutCheckContainsAllFail = "Can't find the expected value in the program's output: {expected}"
    override val tslStdoutContainsAll = "contains all"
    override val tslStdoutContainsOne = "contains at least one"
    override val tslStdoutNotContainsOne = "doesn't contain at least one"
    override val tslStdoutNotContainsAll = "doesn't contain any"
    override val tslStdoutDataString = "strings"
    override val tslStdoutDataNumber = "numbers"
    override val tslStdoutDataLine = "lines"
    override val tslStdoutOrdered = "The values must be in the same order"
    override val tslStdoutCheckSent1 = "Output"
    override val tslStdoutCheckSent2 = "of the following"
    override val tslReturnCheck = "Return value check"
    override val tslReturnCheckPrefixMsg = "The return value must be:"
    override val tslReturnCheckValueHelp = "Expected function return value in Python syntax"
    override val tslReturnCheckPass = "Function returned the correct value {expected}"
    override val tslReturnCheckFail = "Expected the function to return {expected}, but instead it returned {actual}"
    override val exercise = "Exercise"
    override val findSimilarities = "Find similarities"
    override val searching = "Searching..."
    override val topSimilarPairs = "Top similarities"
    override val diceSimilarity = "Sørensen–Dice score"
    override val levenshteinSimilarity = "Levenshtein score"
    override val noAutogradeWarning =
        "This exercise does not have any automatic tests. Add tests or remove the `submit` query parameter."

    override val aboutS1 = "Lahendus is operated and developed by the"
    override val aboutS2 = "Institute of Computer Science at the University of Tartu"
    override val aboutS3 = "Lahendus is based on an open-source application called"
    override val aboutS4 = " which is also developed at the Institute of Computer Science"
    override val aboutS5 =
        "If you have any questions about Lahendus or are interested in its development, or if you found a bug, then come talk to us in"
    override val aboutS6 = "our Discord server"
    override val aboutSponsors =
        "The development of Lahendus and easy, and creating some of the exercises has been supported by"
    override val statsAutograding = "Submissions being autograded"
    override val statsSubmissions = "Total submissions"
    override val statsAccounts = "Total accounts"

    override val today = "today"
    override val yesterday = "yesterday"
    override val tomorrow = "tomorrow"
    override val monthList = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December"
    )
}