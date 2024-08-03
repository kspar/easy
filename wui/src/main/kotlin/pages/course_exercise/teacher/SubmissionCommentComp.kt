package pages.course_exercise.teacher

import EzDate
import kotlinx.coroutines.await
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.dstIfNotNull
import template
import translation.Str

abstract class SubmissionCommentContentComp(parent: Component) : Component(parent)

class SubmissionCommentComp(
    val courseId: String,
    val courseExerciseId: String,
    val submissionId: String,
    val submissionNumber: Int,
    val activityId: String,
    val authorFirstName: String,
    val authorLastName: String,
    val createdAt: EzDate,
    val editedAt: EzDate?,
    var commentAdoc: String,
    var commentHtml: String,
    val grade: Int?,
    val isEditable: Boolean,
    var isEditing: Boolean,
    val onActivitiesChanged: suspend () -> Unit,
    parent: Component
) : Component(parent) {

    private lateinit var commentContent: SubmissionCommentContentComp
    private var commentGradeBadge: SubmissionCommentGradeComp? = null

    override val children: List<Component>
        get() = listOfNotNull(commentContent, commentGradeBadge)

    override fun create() = doInPromise {
        commentContent = if (isEditing)
            SubmissionEditCommentComp(
                courseId, courseExerciseId, submissionId, activityId, commentAdoc, commentHtml,
                onCommentEdited = onActivitiesChanged,
                onCommentCancelled = {
                    isEditing = false
                    createAndBuild().await()
                },
                startVisible = true,
                parent = this
            )
        else
            SubmissionViewCommentComp(
                courseId, courseExerciseId, submissionId, submissionNumber, activityId, commentHtml, isEditable,
                onStartEditing = {
                    isEditing = true
                    createAndBuild().await()
                },
                onCommentDeleted = onActivitiesChanged,
                parent = this
            )

        if (grade != null)
            commentGradeBadge = SubmissionCommentGradeComp(grade, this)
    }

    override fun render() = template(
        """
            <div style='border: 1px solid #bbb; margin: 1rem;'>
                {{teacherName}} | {{time}} {{#edited}}({{editedLabel}} {{editedAt}}){{/edited}} | $commentContent
                <div>
                    ${commentGradeBadge.dstIfNotNull()}
                </div>
            </div>
        """.trimIndent(),
        "teacherName" to "$authorFirstName $authorLastName",
        "time" to createdAt.toHumanString(EzDate.Format.DATE),
        "edited" to editedAt?.let {
            mapOf("editedAt" to maxOf(editedAt, createdAt).toHumanString(EzDate.Format.DATE))
        },
        // TODO: should be "time (muudetud)" with editedAt only visible on hover
        "editedLabel" to Str.editedAt,
    )
}
