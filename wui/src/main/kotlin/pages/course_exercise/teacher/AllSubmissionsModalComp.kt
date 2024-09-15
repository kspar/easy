package pages.course_exercise.teacher

import EzDate
import components.ezcoll.EzCollComp
import components.modal.ModalComp
import dao.CourseExercisesTeacherDAO
import dao.ExerciseDAO
import kotlinx.coroutines.await
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import translation.Str


class AllSubmissionsModalComp(
    val courseId: String,
    val courseExId: String,
    val studentId: String,
    val studentName: String,
    val currentSubmissionId: String,
    parent: Component
) : Component(parent) {

    private lateinit var modalComp: ModalComp<String?>
    private lateinit var list: EzCollComp<CourseExercisesTeacherDAO.StudentSubmissionOld>

    override val children: List<Component>
        get() = listOf(modalComp)

    override fun create() = doInPromise {

        val submissions = CourseExercisesTeacherDAO.getAllSubmissionsForStudent(courseId, courseExId, studentId)
            .await().submissions

        modalComp = ModalComp(
            "${Str.allSubmissions} — $studentName",
            defaultReturnValue = null,
            fixFooter = true,
            isWide = true,
            bodyCompsProvider = {
                list = EzCollComp(
                    submissions.map {
                        EzCollComp.Item(
                            it,
                            EzCollComp.ItemTypeText("# ${it.submission_number}"),
                            it.created_at.toHumanString(EzDate.Format.FULL),
                            titleStatus = if (it.id == currentSubmissionId) EzCollComp.TitleStatus.INACTIVE else EzCollComp.TitleStatus.NORMAL,
                            titleInteraction = if (it.id == currentSubmissionId) null else
                                EzCollComp.TitleAction<CourseExercisesTeacherDAO.StudentSubmissionOld> {
                                    modalComp.closeAndReturnWith(it.id)
                                },
                            topAttr = it.grade?.let {
                                EzCollComp.SimpleAttr(
                                    Str.points,
                                    "${it.grade} / 100",
                                    if (it.is_autograde) ExerciseDAO.GraderType.ICON_AUTO else ExerciseDAO.GraderType.ICON_TEACHER,
                                    topAttrMinWidth = EzCollComp.CollMinWidth.W400
                                )
                            },
                            progressBar = EzCollComp.ProgressBar(it.status.translateToProgress()),
                        )
                    },
                    EzCollComp.Strings(Str.submissionSingular, Str.submissionPlural),
                    compact = true,
                    parent = this
                )

                listOf(list)
            },
            parent = this
        )
    }

    suspend fun open() = modalComp.openWithClosePromise().await()

}
