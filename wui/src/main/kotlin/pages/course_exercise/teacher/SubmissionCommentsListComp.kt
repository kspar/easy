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
import rip.kspar.ezspa.dstIfNotNull
import show
import template
import translation.Str

class SubmissionCommentsListComp(
    val courseId: String,
    val courseExerciseId: String,
    val teacher: TeacherConf?,
    val onlyForSubmissionId: String? = null,
    parent: Component
) : Component(parent) {

    data class TeacherConf(val studentId: String, val latestSubmissionId: String, val canAddComment: Boolean)

    private var addCommentBtn: ButtonComp? = null
    private var newComment: SubmissionEditCommentComp? = null
    private lateinit var comments: List<SubmissionCommentComp>


    override val children: List<Component>
        get() = comments + listOfNotNull(addCommentBtn, newComment)

    override fun create() = doInPromise {
        val activitiesResp = if (teacher != null)
            CourseExercisesTeacherDAO.getActivityForStudent(courseId, courseExerciseId, teacher.studentId).await()
        else
            CourseExercisesStudentDAO.getActivity(courseId, courseExerciseId).await()

        val activities = activitiesResp.teacher_activities.sortedByDescending { it.created_at }

        comments = activities
            .filter {
                onlyForSubmissionId == null || onlyForSubmissionId == it.submission_id
            }
            .map {
                SubmissionCommentComp(
                    courseId, courseExerciseId, it.submission_id, it.submission_number, it.id,
                    it.teacher.given_name, it.teacher.family_name, it.created_at, it.edited_at,
                    it.feedback?.feedback_adoc.orEmpty(), it.feedback?.feedback_html.orEmpty(), it.grade,
                    isEditable = teacher != null && it.teacher.id == Auth.username,
                    isEditing = false,
                    onActivitiesChanged = {
                        createAndBuild().await()
                    },
                    parent = this
                )
            }

        if (teacher != null) {
            if (teacher.canAddComment)
                addCommentBtn = ButtonComp(
                    ButtonComp.Type.TEXT,
                    Str.doAddComment,
                    Icons.add,
                    onClick = {
                        addCommentBtn!!.hide()
                        newComment!!.show()
                    },
                    parent = this
                )

            newComment = SubmissionEditCommentComp(
                courseId, courseExerciseId, teacher.latestSubmissionId, null, "", "",
                onCommentEdited = { createAndBuild().await() },
                onCommentCancelled = {
                    newComment!!.hide()
                    addCommentBtn!!.show()
                },
                startVisible = false,
                parent = this
            )
        }
    }

    override fun render() = template(
        """
            ${addCommentBtn.dstIfNotNull()}
            ${newComment.dstIfNotNull()}
            {{#comments}}
                {{{dst}}}
            {{/comments}}
        """.trimIndent(),
        "comments" to comments.map {
            mapOf("dst" to it.toString())
        }
    )

    override fun postChildrenBuilt() {
        highlightCode()
    }
}
