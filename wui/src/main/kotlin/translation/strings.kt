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
    abstract val doRemove: String
    abstract val removing: String
    abstract val removed: String
    abstract val doDelete: String
    abstract val deleted: String
    abstract val doRestore: String
    abstract val doChange: String
    abstract val doDuplicate: String
    abstract val doExpand: String
    abstract val doAutoAssess: String
    abstract val autoAssessing: String
    abstract val tryAgain: String
    abstract val accountGroup: String
    abstract val doDownload: String
    abstract val downloading: String

    abstract val ezcollEmpty: String
    abstract val ezcollNoMatchingItems: String
    abstract val ezcollApply: String
    abstract val ezcollDoFilter: String
    abstract val ezcollDoSort: String
    abstract val ezcollRemoveFilters: String
    abstract val ezcollShown: String
    abstract val ezcollSelected: String

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
    abstract val exerciseCreated: String
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
    abstract val allStudents: String
    abstract val total: String
    abstract val feedbackEmailNote: String

    abstract val autogradeException: String
    abstract val autogradeFailedMsg: String
    abstract val autogradeCreatedFiles: String
    abstract val autogradeStdIn: String
    abstract val autogradeStdOut: String
    abstract val autogradeNoChecksInTest: String


    // Exercise page
    abstract val testType: String
    abstract val copySuffix: String

    // Similarity page
    abstract val exerciseTitle: String
    abstract val findSimilarities: String
    abstract val searching: String
    abstract val topSimilarPairs: String
    abstract val diceSimilarity: String
    abstract val levenshteinSimilarity: String

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
    override val doRemove = "Eemalda"
    override val removing = "Eemaldan..."
    override val removed = "Eemaldatud"
    override val doDelete = "Kustuta"
    override val deleted = "Kustutatud"
    override val doRestore = "Taasta"
    override val doChange = "Muuda"
    override val doDuplicate = "Tee koopia"
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
    override val graderTypeLabel = "Hindamine"
    override val thresholdLabel = "Lävend"
    override val studentVisibleLabel = "Õpilastele nähtav"
    override val studentVisibleFromTimeLabel = "Muutub nähtavaks"
    override val assStudentVisibleLabel = "Hinnangud õpilastele nähtavad"
    override val lastModifiedLabel = "Viimati muudetud"
    override val submissionTimeLabel = "Esitamise aeg"

    override val studentsSingular = "õpilane"
    override val studentsPlural = "õpilast"
    override val enrolledOnCourseAttrKey = "Kursusel on"

    override val coursesTitleAdmin = "Kõik kursused"
    override val accountData = "Konto seaded"
    override val logOut = "Logi välja"
    override val newExercise = "Uus ülesanne"
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
    override val exerciseCreated = "Ülesanne loodud"
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
    override val ezcollEmpty = "Siin pole veel midagi näidata"
    override val ezcollNoMatchingItems = "Valitud filtritele ei vasta ükski rida"
    override val ezcollApply = "Rakenda..."
    override val ezcollDoFilter = "Filtreeri..."
    override val ezcollDoSort = "Järjesta..."
    override val ezcollRemoveFilters = "Eemalda filtrid"
    override val ezcollShown = "kuvatud"
    override val ezcollSelected = "valitud"
    override val addAssessmentLink = "► Lisa hinnang"
    override val closeToggleLink = "▼ Sulge"
    override val graderTypeAuto = "automaatne"
    override val graderTypeTeacher = "manuaalne"
    override val addAssessmentGradeLabel = "Hinne (0-100)"
    override val addAssessmentFeedbackLabel = "Kommentaar"
    override val addAssessmentGradeValidErr = "Hinne peab olema arv 0 ja 100 vahel."
    override val addAssessmentButtonLabel = "Lisa hinnang"
    override val aaTitle = "Automaatkontroll"
    override val submitSuccessMsg = "Lahendus esitatud"
    override val allStudents = "Kõik õpilased"
    override val total = "kokku"
    override val feedbackEmailNote = "Kui lisasid kommentaari, siis saadetakse see õpilasele emailiga."
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
    override val copySuffix = "(koopia)"
    override val exerciseTitle = "Ülesanne"
    override val findSimilarities = "Leia sarnasused"
    override val searching = "Otsin..."
    override val topSimilarPairs = "Kõige sarnasemad paarid"
    override val diceSimilarity = "Sørensen–Dice'i sarnasus"
    override val levenshteinSimilarity = "Levenshteini sarnasus"

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
    override val noPermissionForPageMsg = "It seems like you have no permission to look here. :("
    override val noCourseAccessPageMsg = "It seems like you have no access to this course. :("
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
    override val cancel = "Cancel"
    override val solutionCodeTabName = "lahendus"
    override val solutionEditorPlaceholder = "Write, paste or drag your solution here..."
    override val exerciseSingular = "exercise"
    override val exercisePlural = "exercises"
    override val doEditTitle = "Edit title"
    override val doMove = "Move"
    override val moving = "Moving..."
    override val doRemove = "Remove"
    override val removing = "Removing..."
    override val removed = "Removed"
    override val doDelete = "Delete"
    override val deleted = "Deleted"
    override val doRestore = "Restore"
    override val doChange = "Change"
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
    override val exerciseCreated = "Exercise created"
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
    override val hardDeadlineLabel = "Closing time"
    override val graderTypeLabel = "Grading"
    override val thresholdLabel = "Threshold"
    override val studentVisibleLabel = "Visible to students"
    override val studentVisibleFromTimeLabel = "Visible from"
    override val assStudentVisibleLabel = "Assessments visible to students"
    override val lastModifiedLabel = "Last modified"
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
    override val ezcollEmpty = "Nothing to see here yet"
    override val ezcollNoMatchingItems = "No items match the selected filters"
    override val ezcollApply = "Apply..."
    override val ezcollDoFilter = "Filter..."
    override val ezcollDoSort = "Sort..."
    override val ezcollRemoveFilters = "Remove filters"
    override val ezcollShown = "shown"
    override val ezcollSelected = "selected"
    override val addAssessmentLink = "► Add assessment"
    override val addAssessmentGradeLabel = "Grade (0-100)"
    override val addAssessmentFeedbackLabel = "Comment"
    override val addAssessmentGradeValidErr = "The grade has to be an integer between 0 and 100."
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

    override val testType = "Test type"
    override val copySuffix = "(copy)"
    override val exerciseTitle = "Exercise"
    override val findSimilarities = "Find similarities"
    override val searching = "Searching..."
    override val topSimilarPairs = "Top similarities"
    override val diceSimilarity = "Sørensen–Dice score"
    override val levenshteinSimilarity = "Levenshtein score"

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