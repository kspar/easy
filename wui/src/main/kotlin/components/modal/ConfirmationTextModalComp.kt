package components.modal

import components.form.OldButtonComp
import components.text.StringComp
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
    primaryBtnType: OldButtonComp.Type = OldButtonComp.Type.PRIMARY,
    override var primaryAction: (suspend () -> Boolean)? = null,
    override var secondaryAction: (suspend () -> Unit)? = null,
    override var primaryPostAction: (suspend () -> Unit)? = null,
    override var secondaryPostAction: (suspend () -> Unit)? = null,
    parent: Component?,
) : BinaryModalComp<Boolean>(
    title,
    primaryBtnText, secondaryBtnText,
    primaryBtnLoadingText, secondaryBtnLoadingText,
    false, primaryBtnType, true, fixFooter, false,
    primaryAction, secondaryAction, primaryPostAction, secondaryPostAction,
    parent = parent,
) {

    private val stringComp = StringComp("", this)

    fun setText(parts: List<StringComp.Part>) {
        stringComp.parts = parts
        stringComp.rebuild()
    }

    fun setText(text: String) = setText(StringComp.simpleText(text))

    override fun create() = doInPromise {
        super.create().await()
        super.setContentComps {
            listOf(stringComp)
        }
    }
}
