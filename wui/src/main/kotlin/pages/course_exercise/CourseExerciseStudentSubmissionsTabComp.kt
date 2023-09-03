package pages.course_exercise

import EzDate
import components.EzCollComp
import dao.CourseExercisesStudentDAO
import kotlinx.coroutines.await
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import translation.Str


class CourseExerciseStudentSubmissionsTabComp(
    private val courseId: String,
    private val courseExId: String,
    private val threshold: Int,
    parent: Component
) : Component(parent) {

    data class Props(
        val number: Int, // TODO: this should come from service
        val solution: String,
        val submissionTime: EzDate,
        val autogradeStatus: CourseExercisesStudentDAO.AutogradeStatus,
        val validGrade: CourseExercisesStudentDAO.ValidGrade?,
    )

    private lateinit var list: EzCollComp<Props>

    override val children: List<Component>
        get() = listOf(list)

    override fun create() = doInPromise {
        val submissions = CourseExercisesStudentDAO.getSubmissions(courseId, courseExId).await()
            .sortedBy { it.submission_time }
            .mapIndexed { i, sub ->
                Props(i + 1, sub.solution, sub.submission_time, sub.autograde_status, sub.validGrade)
            }.reversed()

        list = EzCollComp(
            submissions.map {
                EzCollComp.Item(
                    it,
                    EzCollComp.ItemTypeText("#${it.number}"),
                    it.submissionTime.toHumanString(EzDate.Format.FULL),
                    topAttr = it.validGrade?.let {
                        EzCollComp.SimpleAttr(
                            Str.gradeLabel,
                            "${it.grade}/100",
                            it.grader_type.icon(),
                            topAttrMinWidth = EzCollComp.CollMinWidth.W400
                        )
                    },
                    progressBar = EzCollComp.ProgressBar(calculateSubmissionStatus(it.validGrade, threshold)),
                )
            },
            EzCollComp.Strings(Str.submissionSingular, Str.submissionPlural),
            compact = true,
            parent = this
        )
    }

    private fun calculateSubmissionStatus(validGrade: CourseExercisesStudentDAO.ValidGrade?, threshold: Int) =
        when {
            validGrade == null -> EzCollComp.Progress(0, 0, 1, 0)
            validGrade.grade >= threshold -> EzCollComp.Progress(1, 0, 0, 0)
            else -> EzCollComp.Progress(0, 1, 0, 0)
        }
}


