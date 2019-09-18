// Top-level property to access strings
var Str: TranslatableStrings = EstStrings


abstract class TranslatableStrings {

    // General

    abstract fun noPermissionForPage(): String
    abstract fun somethingWentWrong(): String
    abstract fun errorDismiss(): String
    abstract fun yes(): String
    abstract fun no(): String
    abstract fun myCourses(): String
    abstract fun gradedAutomatically(): String
    abstract fun gradedByTeacher(): String
    abstract fun notGradedYet(): String
    abstract fun closeToggleLink(): String
    fun translateBoolean(bool: Boolean) = if (bool) yes() else no()


    // Navbar

    abstract fun topMenuCourses(): String
    abstract fun accountData(): String
    abstract fun logOut(): String
    abstract fun roleChangeStudent(): String
    abstract fun roleChangeBack(): String
    abstract fun roleChangeStudentSuffix(): String


    // Sidenav

    abstract fun sidenavHeader(): String
    abstract fun newExercise(): String
    abstract fun addExistingExercise(): String
    abstract fun participants(): String
    abstract fun grades(): String


    // Courses page

    abstract fun noCoursesLabel(): String
    abstract fun coursesTitle(): String
    abstract fun coursesTitleAdmin(): String
    abstract fun fetchingCoursesFailed(): String
    abstract fun newCourseLink(): String
    abstract fun coursesStudents(): String
    abstract fun coursesStudent(): String


    // New course page

    abstract fun courseCreationFailed(): String
    abstract fun newCourseName(): String
    abstract fun addNewCourse(): String


    // Exercises page

    abstract fun deadlineLabel(): String
    abstract fun completedLabel(): String
    abstract fun startedLabel(): String
    abstract fun ungradedLabel(): String
    abstract fun unstartedLabel(): String


    // Exercise page

    abstract fun tabExerciseLabel(): String
    abstract fun tabTestingLabel(): String
    abstract fun tabSubmissionsLabel(): String
    abstract fun tabSubmitLabel(): String
    abstract fun softDeadlineLabel(): String
    abstract fun hardDeadlineLabel(): String
    abstract fun graderTypeLabel(): String
    abstract fun thresholdLabel(): String
    abstract fun studentVisibleLabel(): String
    abstract fun assStudentVisibleLabel(): String
    abstract fun lastModifiedLabel(): String
    abstract fun graderTypeAuto(): String
    abstract fun graderTypeTeacher(): String
    abstract fun autoAssessmentLabel(): String
    abstract fun autoGradeLabel(): String
    abstract fun teacherAssessmentLabel(): String
    abstract fun teacherGradeLabel(): String
    abstract fun doAutoAssess(): String
    abstract fun autoAssessing(): String
    abstract fun addAssessmentLink(): String
    abstract fun addAssessmentGradeLabel(): String
    abstract fun addAssessmentFeedbackLabel(): String
    abstract fun addAssessmentGradeValidErr(): String
    abstract fun addAssessmentButtonLabel(): String
    abstract fun assessmentAddedMsg(): String
    abstract fun submissionTimeLabel(): String
    abstract fun submitAndCheckLabel(): String
    abstract fun lastSubmTimeLabel(): String
    abstract fun submissionHeading(): String
    abstract fun latestSubmissionSuffix(): String
    abstract fun allSubmissionsLink(): String
    abstract fun loadingAllSubmissions(): String
    abstract fun oldSubmissionNote(): String
    abstract fun toLatestSubmissionLink(): String
}


private object EstStrings : TranslatableStrings() {
    override fun assessmentAddedMsg() = "Hinnang lisatud."
    override fun oldSubmissionNote() = "See on vana esitus."
    override fun toLatestSubmissionLink() = "Vaata viimast esitust."
    override fun loadingAllSubmissions() = "Laen esitusi..."
    override fun submissionHeading() = "Esitus"
    override fun latestSubmissionSuffix() = "(viimane esitus)"
    override fun allSubmissionsLink() = "► Vaata kõiki esitusi"
    override fun tabSubmitLabel() = "Esitamine"
    override fun submitAndCheckLabel() = "Esita ja kontrolli"
    override fun lastSubmTimeLabel() = "Viimase esituse aeg"
    override fun noCoursesLabel() = "Sind ei ole veel ühelegi kursusele lisatud."
    override fun softDeadlineLabel() = "Tähtaeg"
    override fun hardDeadlineLabel() = "Sulgemise aeg"
    override fun graderTypeLabel() = "Hindamine"
    override fun thresholdLabel() = "Lävend"
    override fun studentVisibleLabel() = "Õpilastele nähtav"
    override fun assStudentVisibleLabel() = "Hinnangud õpilastele nähtavad"
    override fun lastModifiedLabel() = "Viimati muudetud"
    override fun submissionTimeLabel() = "Esitamise aeg"
    override fun yes() = "jah"
    override fun no() = "ei"
    override fun errorDismiss() = "Sain aru"
    override fun topMenuCourses() = "Minu kursused"
    override fun noPermissionForPage() = "Teil puudub õigus selle lehe vaatamiseks."
    override fun newCourseLink() = "Uus kursus"
    override fun coursesStudents() = "õpilast"
    override fun coursesStudent() = "õpilane"
    override fun addNewCourse() = "Lisa uus kursus"
    override fun newCourseName() = "Uue kursuse nimi"
    override fun somethingWentWrong() = "Midagi läks valesti... Proovi lehte uuendada ja kui viga kordub, siis võta ühendust administraatoriga."
    override fun fetchingCoursesFailed() = "Kursuste laadimine ebaõnnestus."
    override fun courseCreationFailed() = "Uue kursuse loomine ebaõnnestus."
    override fun coursesTitleAdmin() = "Kõik kursused"
    override fun accountData() = "Konto andmed"
    override fun logOut() = "Logi välja"
    override fun roleChangeStudent() = "Digimuutu õpilaseks"
    override fun roleChangeBack() = "Taasmuutu põhirollile"
    override fun roleChangeStudentSuffix() = " (õpilane)"
    override fun sidenavHeader() = "Kursuse seaded"
    override fun newExercise() = "Uus ülesanne"
    override fun addExistingExercise() = "Lisa olemasolev ülesanne"
    override fun participants() = "Osalejad"
    override fun grades() = "Hinded"
    override fun coursesTitle() = "Minu kursused"
    override fun myCourses() = "Minu kursused"
    override fun deadlineLabel() = "Tähtaeg"
    override fun gradedAutomatically() = "Automaatselt hinnatud"
    override fun gradedByTeacher() = "Õpetaja poolt hinnatud"
    override fun notGradedYet() = "Hindamata"
    override fun completedLabel() = "Lõpetanud"
    override fun startedLabel() = "Nässu läinud"
    override fun ungradedLabel() = "Hindamata"
    override fun unstartedLabel() = "Esitamata"
    override fun tabExerciseLabel() = "Ülesanne"
    override fun tabTestingLabel() = "Katsetamine"
    override fun tabSubmissionsLabel() = "Esitused"
    override fun autoAssessmentLabel() = "Automaatne hinnang"
    override fun autoGradeLabel() = "Automaatne hinne"
    override fun teacherAssessmentLabel() = "Õpetaja hinnang"
    override fun teacherGradeLabel() = "Hinne"
    override fun doAutoAssess() = "Kontrolli"
    override fun autoAssessing() = "Kontrollin..."
    override fun addAssessmentLink() = "► Lisa hinnang"
    override fun closeToggleLink() = "▼ Sulge"
    override fun graderTypeAuto() = "automaatne"
    override fun graderTypeTeacher() = "manuaalne"
    override fun addAssessmentGradeLabel() = "Hinne (0-100)"
    override fun addAssessmentFeedbackLabel() = "Tagasiside"
    override fun addAssessmentGradeValidErr() = "Hinne peab olema arv 0 ja 100 vahel."
    override fun addAssessmentButtonLabel() = "Lisa hinnang"
}
