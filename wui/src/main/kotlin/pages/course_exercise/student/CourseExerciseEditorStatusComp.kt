package pages.course_exercise.student

import Icons
import rip.kspar.ezspa.Component
import template

class CourseExerciseEditorStatusComp(
    var msg: String,
    var status: Status,
    parent: Component
) : Component(parent) {

    enum class Status { IN_SYNC, WAITING, SYNCING, SYNC_FAILED }

    override fun render() = template(
        """
            <div style="position: absolute; right: 1.5rem; top: .5rem; display: flex; align-items: center" class='icon-med'>
                <span style='padding-right: 1rem; color: var(--ez-icon-col); cursor: default;'>{{msg}}</span>
                {{{icon}}}            
            </div>
        """.trimIndent(),
        "msg" to msg,
        "icon" to when (status) {
            Status.IN_SYNC -> Icons.cloudSuccess
            Status.WAITING -> Icons.dotsHorizontal
            Status.SYNCING -> Icons.spinner
            Status.SYNC_FAILED -> Icons.cloudFail
        }
    )
}