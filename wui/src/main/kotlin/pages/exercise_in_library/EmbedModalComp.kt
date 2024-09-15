package pages.exercise_in_library

import components.modal.ModalComp
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import translation.Str

class EmbedModalComp(
    private val exerciseId: String,
    private val canEdit: Boolean,
    private val courseId: String? = null,
    private val courseExId: String? = null,
    private val titleAlias: String? = null,
) : Component() {

    private lateinit var modalComp: ModalComp<Unit>
    private lateinit var options: EmbedModalOptionsComp

    override val children: List<Component>
        get() = listOf(modalComp)

    override fun create() = doInPromise {

        options = EmbedModalOptionsComp(exerciseId, canEdit, courseId, courseExId, titleAlias)

        modalComp = ModalComp(
            Str.embedding,
            defaultReturnValue = Unit,
            isWide = true,
            onOpened = {
                options.createAndBuild()
            },
            bodyCompsProvider = {
                listOfNotNull(options)
            },
            parent = this
        )
    }

    fun openWithClosePromise() = modalComp.openWithClosePromise()
}