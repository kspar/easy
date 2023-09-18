package pages.exercise_in_library.editor.tsl

import components.form.RadioButtonsComp
import components.modal.BinaryModalComp
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import translation.Str

class TSLReorderTestModalComp(
    parent: Component,
) : Component(parent) {

    data class MovableItem(val id: String, val title: String, val idx: Int)

    var movingItem: MovableItem? = null
    var allItems: List<MovableItem> = emptyList()

    private lateinit var radioBtns: RadioButtonsComp

    private val modalComp: BinaryModalComp<Int?> = BinaryModalComp(
        null,
        Str.doMove, Str.cancel, Str.moving,
        defaultReturnValue = null,
        primaryButtonEnabledInitial = false,
        fixFooter = true,
        primaryAction = { radioBtns.getSelectedOption()?.value?.toInt() },
        parent = this
    )

    override val children: List<Component>
        get() = listOf(modalComp)

    override fun create() = doInPromise {
        val ex = movingItem

        val buttons = if (ex != null) {

            val oldIdx = ex.idx

            val firstBtn = RadioButtonsComp.Button(
                "", "0",
                if (oldIdx == 0)
                    RadioButtonsComp.Type.DISABLED else
                    RadioButtonsComp.Type.SELECTABLE
            )

            val otherBtns = (allItems - ex).mapIndexed { i, e ->
                RadioButtonsComp.Button(
                    e.title, "${i + 1}",
                    type = if (oldIdx == e.idx + 1)
                        RadioButtonsComp.Type.DISABLED
                    else
                        RadioButtonsComp.Type.SELECTABLE
                )
            }

            listOf(firstBtn) + otherBtns
        } else emptyList()

        radioBtns = RadioButtonsComp(
            buttons, selectLineAfterButtons = true,
            onValidChange = { modalComp.primaryButton.setEnabled(it) },
            parent = modalComp
        )

        modalComp.setContentComps { listOf(radioBtns) }
    }

    override fun postChildrenBuilt() {
        radioBtns.validateInitial()
    }

    fun openWithClosePromise() = modalComp.openWithClosePromise()
}
