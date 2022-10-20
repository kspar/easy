// Top-level property to access strings
var Str: TranslatableStrings = EstStrings


abstract class TranslatableStrings {

    // General

    abstract fun notFoundPageTitle(): String
    abstract fun notFoundPageMsg(): String
    abstract fun noPermissionForPageTitle(): String
    abstract fun noPermissionForPageMsg(): String
    abstract fun noCourseAccessPageTitle(): String
    abstract fun noCourseAccessPageMsg(): String
    abstract fun somethingWentWrong(): String
    abstract fun yes(): String
    abstract fun no(): String
    abstract fun myCourses(): String
    abstract fun exerciseLibrary(): String
    abstract fun gradedAutomatically(): String
    abstract fun gradedByTeacher(): String
    abstract fun notGradedYet(): String
    abstract fun closeToggleLink(): String
    abstract fun doSave(): String
    abstract fun saving(): String
    abstract fun doAdd(): String
    abstract fun adding(): String
    abstract fun cancel(): String
    abstract fun solutionCodeTabName(): String
    abstract fun new(): String
    abstract fun exercise(): String
    abstract fun exercises(): String

    fun translateBoolean(bool: Boolean) = if (bool) yes() else no()
    fun translateRole(role: Role) = when (role) {
        Role.STUDENT -> roleStudent()
        Role.TEACHER -> roleTeacher()
        Role.ADMIN -> roleAdmin()
    }
    fun translateStudents(count: Int) = if (count == 1) coursesStudent() else coursesStudents()
    fun translateExercises(count: Int) = if (count == 1) exercise() else exercises()


    // Navbar

    abstract fun topMenuCourses(): String
    abstract fun activeRoleLabel(): String
    abstract fun roleAdmin(): String
    abstract fun roleTeacher(): String
    abstract fun roleStudent(): String
    abstract fun accountData(): String
    abstract fun logOut(): String
    abstract fun roleChangeStudentSuffix(): String


    // Sidenav

    abstract fun sidenavHeader(): String
    abstract fun newExercise(): String
    abstract fun addExistingExercise(): String
    abstract fun participants(): String
    abstract fun gradesLabel(): String


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


    // Course exercise page

    abstract fun tabExerciseLabel(): String
    abstract fun tabTestingLabel(): String
    abstract fun tabSubmissionsLabel(): String
    abstract fun tabSubmitLabel(): String
    abstract fun softDeadlineLabel(): String
    abstract fun hardDeadlineLabel(): String
    abstract fun graderTypeLabel(): String
    abstract fun thresholdLabel(): String
    abstract fun studentVisibleLabel(): String
    abstract fun studentVisibleFromTimeLabel(): String
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
    abstract fun aaTitle(): String
    abstract fun submitSuccessMsg(): String


    // Exercise page

    abstract fun usedOnCoursesLabel(): String
    abstract fun previewLabel(): String
    abstract fun exerciseSaved(): String


}


private object EstStrings : TranslatableStrings() {
    override fun solutionCodeTabName() = "lahendus"
    override fun activeRoleLabel() = "Aktiivne roll:"
    override fun roleAdmin() = "Admin"
    override fun roleTeacher() = "Õpetaja"
    override fun roleStudent() = "Õpilane"
    override fun usedOnCoursesLabel() = "Kasutusel kursustel"
    override fun previewLabel() = "Eelvaade"
    override fun doSave() = "Salvesta"
    override fun saving() = "Salvestan..."
    override fun doAdd() = "Lisa"
    override fun adding() = "Lisan..."
    override fun cancel() = "Tühista"
    override fun exerciseSaved() = "Ülesanne uuendatud"
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
    override fun studentVisibleFromTimeLabel() = "Muutub nähtavaks"
    override fun assStudentVisibleLabel() = "Hinnangud õpilastele nähtavad"
    override fun lastModifiedLabel() = "Viimati muudetud"
    override fun submissionTimeLabel() = "Esitamise aeg"
    override fun yes() = "jah"
    override fun no() = "ei"
    override fun topMenuCourses() = "Minu kursused"
    override fun noPermissionForPageMsg() = "Sul puudub õigus selle lehe vaatamiseks. :("
    override fun newCourseLink() = "Uus kursus"
    override fun coursesStudents() = "õpilast"
    override fun coursesStudent() = "õpilane"
    override fun addNewCourse() = "Lisa uus kursus"
    override fun newCourseName() = "Uue kursuse nimi"
    override fun somethingWentWrong() = "Midagi läks valesti... Proovi lehte uuendada ja kui viga kordub, siis võta ühendust administraatoriga."
    override fun fetchingCoursesFailed() = "Kursuste laadimine ebaõnnestus."
    override fun courseCreationFailed() = "Uue kursuse loomine ebaõnnestus."
    override fun coursesTitleAdmin() = "Kõik kursused"
    override fun accountData() = "Konto seaded"
    override fun logOut() = "Logi välja"
    override fun roleChangeStudentSuffix() = " (õpilane)"
    override fun sidenavHeader() = "Kursuse seaded"
    override fun newExercise() = "Uus ülesanne"
    override fun addExistingExercise() = "Lisa olemasolev ülesanne"
    override fun participants() = "Osalejad"
    override fun gradesLabel() = "Hinded"
    override fun coursesTitle() = "Minu kursused"
    override fun myCourses() = "Minu kursused"
    override fun exerciseLibrary() = "Ülesandekogu"
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
    override fun aaTitle() = "Automaatkontroll"
    override fun submitSuccessMsg() = "Lahendus esitatud"
    override fun noPermissionForPageTitle() = "Ligipääs puudub"
    override fun noCourseAccessPageTitle() = "Ligipääs kursusele puudub"
    override fun noCourseAccessPageMsg() = "Sul puudub ligipääs sellele kursusele. :("
    override fun notFoundPageTitle() = "Lehte ei leitud"
    override fun notFoundPageMsg() = "Siin pole midagi kasulikku näha. :("
    override fun new() = "uus"
    override fun exercise() = "ülesanne"
    override fun exercises() = "ülesannet"
}
