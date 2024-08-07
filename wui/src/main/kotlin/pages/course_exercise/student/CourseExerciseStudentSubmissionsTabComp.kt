package pages.course_exercise.student

import EzDate
import components.EzCollComp
import dao.CourseExercisesStudentDAO
import dao.ExerciseDAO
import kotlinx.coroutines.await
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import translation.Str


class CourseExerciseStudentSubmissionsTabComp(
    private val courseId: String,
    private val courseExId: String,
    private val onOpenSubmission: suspend (CourseExercisesStudentDAO.StudentSubmission) -> Unit,
    parent: Component
) : Component(parent) {

    private lateinit var list: EzCollComp<CourseExercisesStudentDAO.StudentSubmission>

    override val children: List<Component>
        get() = listOf(list)

    override fun create() = doInPromise {
        val submissions = CourseExercisesStudentDAO.getSubmissions(courseId, courseExId).await()

        list = EzCollComp(
            submissions.map {
                EzCollComp.Item(
                    it,
                    EzCollComp.ItemTypeText("# ${it.number}"),
                    it.submission_time.toHumanString(EzDate.Format.FULL),
                    titleInteraction = EzCollComp.TitleAction<CourseExercisesStudentDAO.StudentSubmission> {
                        onOpenSubmission(it)
                    },
                    topAttr = it.grade?.let {
                        EzCollComp.SimpleAttr(
                            Str.pointsLabel,
                            "${it.grade} / 100",
                            if (it.is_autograde) ExerciseDAO.GraderType.ICON_AUTO else ExerciseDAO.GraderType.ICON_TEACHER,
                            topAttrMinWidth = EzCollComp.CollMinWidth.W400
                        )
                    },
                    progressBar = EzCollComp.ProgressBar(it.submission_status.translateToProgress()),
                )
            },
            EzCollComp.Strings(Str.submissionSingular, Str.submissionPlural),
            compact = true,
            parent = this
        )
    }
}
