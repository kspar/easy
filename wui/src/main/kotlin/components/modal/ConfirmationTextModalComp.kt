package components.modal

import components.StringComp
import components.form.ButtonComp
import kotlinx.coroutines.await
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise


class ConfirmationTextModalComp(
    title: String?,
    primaryBtnText: String,
    secondaryBtnText: String,
    primaryBtnLoadingText: String? = null,
    secondaryBtnLoadingText: String? = null,
    fixFooter: Boolean = false,
    primaryBtnType: ButtonComp.Type = ButtonComp.Type.PRIMARY,
    override var primaryAction: (suspend () -> Boolean)? = null,
    override var secondaryAction: (suspend () -> Unit)? = null,
    override var primaryPostAction: (suspend () -> Unit)? = null,
    override var secondaryPostAction: (suspend () -> Unit)? = null,
    parent: Component?,
) : BinaryModalComp<Boolean>(
    title,
    primaryBtnText, secondaryBtnText,
    primaryBtnLoadingText, secondaryBtnLoadingText,
    false, primaryBtnType, fixFooter, false,
    primaryAction, secondaryAction, primaryPostAction, secondaryPostAction,
    parent
) {

    private val stringComp = StringComp("", this)

    var text: String
        get() = stringComp.text
        set(value) {
            stringComp.text = value
            stringComp.rebuild()
        }

    override fun create() = doInPromise {
        super.create().await()
        super.setContentComps {
            listOf(stringComp)
        }
    }
}
