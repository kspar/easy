package pages.course_exercise.student

import Icons
import components.DropdownIconMenuComp
import components.DropdownMenuComp
import rip.kspar.ezspa.Component
import template
import translation.Str

class CourseExerciseEditorStatusComp(
    var msg: String,
    var status: Status,
    canUpload: Boolean,
    onUpload: () -> Unit,
    onDownload: () -> Unit,
    parent: Component
) : Component(parent) {

    enum class Status { IN_SYNC, WAITING, SYNCING, SYNC_FAILED }

    private val menu = DropdownIconMenuComp(
        Icons.dotsVertical, Str.editorMenuLabel, listOf(
            DropdownMenuComp.Item(Str.uploadSubmission, Icons.upload, isDisabled = !canUpload, onSelected = onUpload),
            DropdownMenuComp.Item(Str.downloadSubmission, Icons.download, onSelected = onDownload),
        ), this
    )

    override val children: List<Component>
        get() = listOf(menu)

    override fun render() = template(
        """
            <div style="position: absolute; right: .4rem; top: .2rem; display: flex; align-items: center" class='icon-med'>
                <span style='padding-right: 1rem; color: var(--ez-icon-col); cursor: default;'>{{msg}}</span>
                <ez-flex style='min-width: 2.4rem; justify-content: center;'>{{{icon}}}</ez-flex>
                <ez-flex style='margin-left: .5rem'>
                    $menu
                </ez-flex>
            </div>
        """.trimIndent(),
        "msg" to msg,
        "icon" to when (status) {
            Status.IN_SYNC -> Icons.cloudSuccess
            // Not showing this state - can possibly even remove this state in the future if we don't decide to use it
            Status.WAITING -> Icons.cloudSuccess
            Status.SYNCING -> Icons.spinner
            Status.SYNC_FAILED -> Icons.cloudFail
        }
    )
}