package pages.course_exercise.teacher

import Auth
import Icons
import components.ButtonComp
import dao.CourseExercisesStudentDAO
import dao.CourseExercisesTeacherDAO
import hide
import highlightCode
import kotlinx.coroutines.await
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import show
import template
import translation.Str

class SubmissionCommentsListComp(
    val courseId: String,
    val courseExerciseId: String,
    val studentId: String,
    val latestSubmissionId: String,
    val canAddComment: Boolean,
    val isTeacher: Boolean,
    parent: Component
) : Component(parent) {

    private lateinit var addCommentBtn: ButtonComp
    private lateinit var newComment: SubmissionEditCommentComp
    private lateinit var comments: List<SubmissionCommentComp>

    override val children: List<Component>
        get() = comments + addCommentBtn + newComment

    override fun create() = doInPromise {
        val activitiesResp = if (isTeacher)
            CourseExercisesTeacherDAO.getActivityForStudent(courseId, courseExerciseId, studentId).await()
        else
            CourseExercisesStudentDAO.getActivity(courseId, courseExerciseId).await()

        val activities = activitiesResp.teacher_activities.sortedByDescending { it.created_at }

        comments = activities.map {
            SubmissionCommentComp(
                courseId, courseExerciseId, it.submission_id, it.submission_number, it.id,
                it.teacher.given_name, it.teacher.family_name, it.created_at, it.edited_at,
                it.feedback?.feedback_adoc.orEmpty(), it.feedback?.feedback_html.orEmpty(), it.grade,
                isTeacher && it.teacher.id == Auth.username, false,
                onActivitiesChanged = {
                    createAndBuild().await()
                },
                parent = this
            )
        }

        addCommentBtn = ButtonComp(
            ButtonComp.Type.TEXT,
            Str.doAddComment,
            Icons.add,
            onClick = {
                addCommentBtn.hide()
                newComment.show()
            },
            parent = this
        )

        newComment = SubmissionEditCommentComp(
            courseId, courseExerciseId, latestSubmissionId, null, "", "",
            onCommentEdited = { createAndBuild().await() },
            onCommentCancelled = {
                newComment.hide()
                addCommentBtn.show()
            },
            startVisible = false,
            parent = this
        )
    }

    override fun render() = template(
        """
            $addCommentBtn
            $newComment
            {{#comments}}
                {{{dst}}}
            {{/comments}}
        """.trimIndent(),
        "comments" to comments.map {
            mapOf("dst" to it.toString())
        }
    )

    override fun postRender() {
        if (!canAddComment)
            addCommentBtn.hide()
    }

    override fun postChildrenBuilt() {
        highlightCode()
    }
}
